/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.util;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import jdk.internal.util.Preconditions;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * A list that is its own buffer: one object holds the size, the capacity and the elements, so an
 * element is one reference away from the list instead of two (a list, then its array).
 *
 * <p>The capacity of a list is fixed when it is allocated, and is at least 1: a list without room
 * would be replaced by its first addition. An operation that needs more room
 * ({@link #add(Object)}, {@link #add(int, Object)}, {@link #addAll(Collection)},
 * {@link #ensureCapacity(int)}) returns a larger list that replaces this one; {@link #trimToSize()}
 * returns a smaller one. Use the returned list:
 * <pre>{@code
 *     FastArrayList<String> list = FastArrayList.withCapacity(16);
 *     for (String s : source)
 *         list = list.add(s);
 * }</pre>
 * A list that was replaced throws {@link IllegalStateException} from every method. Other
 * operations work in place and never replace the list.
 *
 * <p>This class is not a {@link List}: {@code List.add} adds in place, which a full list of fixed
 * capacity cannot do. {@link #asList()} returns a {@code List} view that follows the replacements
 * of the list it was made from, at the cost of one more reference per access.
 *
 * <p>Iterators fail fast on a change of the size: they throw {@link ConcurrentModificationException}
 * when the size changed after they were created, other than through the iterator itself. Changes
 * that keep the size ({@link #set}, a removal followed by an addition) are not detected.
 *
 * <p>The class is not synchronized. Unsynchronized concurrent use may lose updates or throw, but
 * never reads or writes outside the list.
 *
 * <p>A non-standard class of this runtime.
 *
 * @param <E> the type of the elements
 */
public final class FastArrayList<E> implements Iterable<E>, RandomAccess, Serializable {
    @Serial
    private static final long serialVersionUID = -2393474218547326715L;

    private static final Unsafe U = Unsafe.getUnsafe();
    /** log2 of the size of a heap reference. */
    private static final int REF_SHIFT = Unsafe.ARRAY_OBJECT_INDEX_SCALE == 4 ? 2 : 3;
    /** The capacity of a larger list that replaces a list of smaller capacity, at least. */
    private static final int DEFAULT_CAPACITY = 10;
    /** The size of a list that was replaced by another. */
    private static final int REPLACED = -1;

    /** Byte offset of element 0. */
    private static final long DATA_BASE;
    /** The largest capacity a list can have. */
    private static final int MAX_CAPACITY;

    static {
        registerNatives();
        DATA_BASE = dataBase();
        MAX_CAPACITY = maxCapacity();
    }

    /*
     * Instance state. The VM lays the elements out after these fields (InstanceSwissTableBufferKlass):
     *   size   number of elements; REPLACED once a larger (or smaller) list replaced this one
     *   cap    the capacity, written by the VM at allocation and never changed; a long, so that no
     *          two adjacent hot fields have the same size (see the VM layout comment)
     * The elements 0..size-1 are at DATA_BASE + (i << REF_SHIFT); the slots from size to cap are null.
     * Memory safety does not depend on the size being right: every element access checks its index
     * against the size, which is always between REPLACED and cap, and cap never changes. Each method
     * reads the size once and bounds all its slot accesses by that value (or by a snapshot of an
     * iterator), so that a racing change cannot make it access a slot outside [0, cap).
     */
    private transient int size;
    private final transient long cap;

    private FastArrayList() {
        throw new AssertionError("allocated by the VM");
    }

    /** A zeroed list of the given capacity; IllegalArgumentException for a capacity out of range. */
    @IntrinsicCandidate
    private static native FastArrayList<?> allocate(int capacity);

    private static native int dataBase();

    private static native int maxCapacity();

    private static native void registerNatives();

    // ---- construction ------------------------------------------------------------------------

    /**
     * Returns an empty list of the given capacity, or of capacity 1 for 0: there are no lists without
     * room.
     *
     * @param <E> the type of the elements
     * @param capacity the capacity
     * @return an empty list
     * @throws IllegalArgumentException if the capacity is negative or larger than this runtime
     *         supports
     */
    @SuppressWarnings("unchecked")
    public static <E> FastArrayList<E> withCapacity(int capacity) {
        if (capacity < 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Illegal capacity: " + capacity);
        }
        return (FastArrayList<E>) allocate(Math.max(capacity, 1));
    }

    /**
     * Returns a list of the elements of a collection, in the order of its iterator, with as much
     * capacity as elements (at least 1).
     *
     * @param <E> the type of the elements
     * @param c the collection
     * @return a new list
     * @throws NullPointerException if the collection is null
     */
    public static <E> FastArrayList<E> copyOf(Collection<? extends E> c) {
        Object[] a = c.toArray();
        FastArrayList<E> l = withCapacity(a.length);
        for (int i = 0; i < a.length; i++) {
            l.put(i, a[i]);
        }
        l.size = a.length;
        return l;
    }

    // ---- element slots -----------------------------------------------------------------------

    private static long offset(int index) {
        return DATA_BASE + ((long) index << REF_SHIFT);
    }

    @SuppressWarnings("unchecked")
    private E at(int index) {
        return (E) U.getReference(this, offset(index));
    }

    private void put(int index, Object e) {
        U.putReference(this, offset(index), e);
    }

    /**
     * Copies n slots of src from srcPos to the slots of dst from dstPos, as System.arraycopy copies the
     * elements of arrays (the ranges may overlap). Both ranges are checked against the capacities, so
     * that no copy reaches outside a list whatever its caller computed.
     */
    private static void copySlots(FastArrayList<?> src, int srcPos, FastArrayList<?> dst, int dstPos, int n) {
        Preconditions.checkFromIndexSize(srcPos, n, (int) src.cap, Preconditions.AIOOBE_FORMATTER);
        Preconditions.checkFromIndexSize(dstPos, n, (int) dst.cap, Preconditions.AIOOBE_FORMATTER);
        copySlots0(src, srcPos, dst, dstPos, n);
    }

    /**
     * copySlots without the checks. C2 replaces the loops by a call of the object-array copy stub
     * (StubRoutines::oop_arraycopy), which applies the collector's barriers to the whole destination
     * range at once.
     */
    @IntrinsicCandidate
    private static void copySlots0(FastArrayList<?> src, int srcPos, FastArrayList<?> dst, int dstPos, int n) {
        if (src == dst && srcPos < dstPos) {
            for (int i = n - 1; i >= 0; i--) {
                dst.put(dstPos + i, src.at(srcPos + i));
            }
        } else {
            for (int i = 0; i < n; i++) {
                dst.put(dstPos + i, src.at(srcPos + i));
            }
        }
    }

    /** Raises the exception for an index out of [0, size), size being REPLACED for a replaced list. */
    private static final class OutOfBounds implements BiFunction<String, List<Number>, RuntimeException> {
        @Override
        public RuntimeException apply(String check, List<Number> args) {
            int length = args.get(1).intValue();
            return length < 0 ? replaced()
                              : new IndexOutOfBoundsException("Index " + args.get(0) + " out of bounds for length " + length);
        }
    }

    private static final OutOfBounds OUT_OF_BOUNDS = new OutOfBounds();

    private static IllegalStateException replaced() {
        return new IllegalStateException("this FastArrayList was replaced: use the list returned by"
                                         + " add, addAll, ensureCapacity or trimToSize");
    }

    /** The size of a list that was not replaced. */
    @ForceInline
    private int liveSize() {
        int s = size;
        if (s < 0) {
            throw replaced();
        }
        return s;
    }

    /** Marks this list replaced by its successor, which already holds the elements. */
    private <T> FastArrayList<T> replacedBy(FastArrayList<T> successor) {
        size = REPLACED;
        return successor;
    }

    /**
     * A list of the given capacity with the elements of this one, which it replaces. The size is read
     * once, and at most capacity elements are copied: a racing change of this list must not make the
     * copy write beyond the new list.
     */
    private FastArrayList<E> moveTo(int capacity) {
        int s = Math.min(liveSize(), capacity);
        FastArrayList<E> n = withCapacity(capacity);
        copySlots(this, 0, n, 0, s);
        n.size = s;
        return replacedBy(n);
    }

    /**
     * A capacity of at least minCapacity, grown like java.util.ArrayList grows its array: by half,
     * and to at least DEFAULT_CAPACITY from a smaller capacity (as ArrayList's first growth).
     */
    private int grownCapacity(int minCapacity) {
        int old = (int) cap;
        int c = old < DEFAULT_CAPACITY ? Math.max(DEFAULT_CAPACITY, minCapacity)
                                       : ArraysSupport.newLength(old, minCapacity - old, old >> 1);
        if (c > MAX_CAPACITY) {
            if (minCapacity > MAX_CAPACITY) {
                throw new OutOfMemoryError("Required FastArrayList capacity " + minCapacity + " is too large");
            }
            c = MAX_CAPACITY;
        }
        return c;
    }

    // ---- queries -----------------------------------------------------------------------------

    /**
     * {@return the number of elements}
     * @throws IllegalStateException if this list was replaced
     */
    @ForceInline
    public int size() {
        return liveSize();
    }

    /**
     * {@return the number of elements this list can hold}
     * @throws IllegalStateException if this list was replaced
     */
    @ForceInline
    public int capacity() {
        liveSize();
        return (int) cap;
    }

    /**
     * {@return whether this list has no elements}
     * @throws IllegalStateException if this list was replaced
     */
    @ForceInline
    public boolean isEmpty() {
        return liveSize() == 0;
    }

    /**
     * Returns the element at an index.
     *
     * @param index the index
     * @return the element
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     * @throws IllegalStateException if this list was replaced
     */
    public E get(int index) {
        Preconditions.checkIndex(index, size, OUT_OF_BOUNDS);
        return at(index);
    }

    /**
     * Replaces the element at an index.
     *
     * @param index the index
     * @param element the new element
     * @return the element that was at the index
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     * @throws IllegalStateException if this list was replaced
     */
    public E set(int index, E element) {
        Preconditions.checkIndex(index, size, OUT_OF_BOUNDS);
        E old = at(index);
        put(index, element);
        return old;
    }

    /**
     * {@return the index of the first element equal to o, or -1}
     * @param o the element to look for
     * @throws IllegalStateException if this list was replaced
     */
    public int indexOf(Object o) {
        int s = liveSize();
        for (int i = 0; i < s; i++) {
            if (Objects.equals(o, at(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return whether this list has an element equal to o}
     * @param o the element to look for
     * @throws IllegalStateException if this list was replaced
     */
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    // ---- changes that may replace the list ---------------------------------------------------

    /**
     * Appends an element. Returns this list if it had room, else a larger list with the elements of
     * this one and the new element, which replaces this list.
     *
     * @param e the element
     * @return the list to use from now on
     * @throws IllegalStateException if this list was replaced
     */
    public FastArrayList<E> add(E e) {
        int s = size;
        if ((s & 0xFFFFFFFFL) >= cap) {        // full, or replaced (size -1)
            return addToLarger(e);
        }
        put(s, e);
        size = s + 1;
        return this;
    }

    private FastArrayList<E> addToLarger(E e) {
        int s = liveSize();
        FastArrayList<E> n = moveTo(grownCapacity(s + 1));
        n.put(s, e);
        n.size = s + 1;
        return n;
    }

    /**
     * Inserts an element at an index, moving the elements from that index one up. Returns this list
     * if it had room, else a larger list, which replaces this one.
     *
     * @param index the index, in {@code [0, size()]}
     * @param element the element
     * @return the list to use from now on
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size()]}
     * @throws IllegalStateException if this list was replaced
     */
    public FastArrayList<E> add(int index, E element) {
        int s = liveSize();
        Objects.checkIndex(index, s + 1);
        FastArrayList<E> l = s < cap ? this : moveTo(grownCapacity(s + 1));
        copySlots(l, index, l, index + 1, s - index);
        l.put(index, element);
        l.size = s + 1;
        return l;
    }

    /**
     * Appends the elements of a collection, in the order of its iterator. Returns this list if it
     * had room, else a larger list, which replaces this one.
     *
     * @param c the collection
     * @return the list to use from now on
     * @throws NullPointerException if the collection is null
     * @throws IllegalStateException if this list was replaced
     */
    public FastArrayList<E> addAll(Collection<? extends E> c) {
        Object[] a = c.toArray();
        int s = liveSize();
        if (a.length == 0) {
            return this;
        }
        FastArrayList<E> l = a.length <= cap - s ? this : moveTo(grownCapacity(s + a.length));
        for (int i = 0; i < a.length; i++) {
            l.put(s + i, a[i]);
        }
        l.size = s + a.length;
        return l;
    }

    /**
     * Returns this list if its capacity is at least minCapacity, else a larger list with the
     * elements of this one, which replaces this list.
     *
     * @param minCapacity the capacity wanted
     * @return the list to use from now on
     * @throws IllegalStateException if this list was replaced
     */
    public FastArrayList<E> ensureCapacity(int minCapacity) {
        liveSize();
        return minCapacity <= cap ? this : moveTo(grownCapacity(minCapacity));
    }

    /**
     * Returns this list if its capacity equals its size (or is 1 for an empty list), else a list of
     * capacity size() (at least 1) with the elements of this one, which replaces this list.
     *
     * @return the list to use from now on
     * @throws IllegalStateException if this list was replaced
     */
    public FastArrayList<E> trimToSize() {
        int c = Math.max(liveSize(), 1);
        return c == cap ? this : moveTo(c);
    }

    // ---- changes in place --------------------------------------------------------------------

    /**
     * Removes the element at an index, moving the elements after it one down.
     *
     * @param index the index
     * @return the element that was removed
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     * @throws IllegalStateException if this list was replaced
     */
    public E remove(int index) {
        int s = size;                              // read once: every slot written below is under s
        Preconditions.checkIndex(index, s, OUT_OF_BOUNDS);
        E old = at(index);
        copySlots(this, index + 1, this, index, s - index - 1);
        put(s - 1, null);
        size = s - 1;
        return old;
    }

    /**
     * Removes the last element.
     *
     * @return the element that was removed
     * @throws NoSuchElementException if this list is empty
     * @throws IllegalStateException if this list was replaced
     */
    public E removeLast() {
        int s = liveSize();
        if (s == 0) {
            throw new NoSuchElementException();
        }
        E old = at(s - 1);
        put(s - 1, null);
        size = s - 1;
        return old;
    }

    /**
     * Removes all elements. The capacity does not change.
     *
     * @throws IllegalStateException if this list was replaced
     */
    public void clear() {
        int s = liveSize();
        for (int i = 0; i < s; i++) {
            put(i, null);
        }
        size = 0;
    }

    // ---- bulk reads --------------------------------------------------------------------------

    /**
     * {@return an array of the elements, in order}
     * @throws IllegalStateException if this list was replaced
     */
    public Object[] toArray() {
        int s = liveSize();
        Object[] a = new Object[s];
        for (int i = 0; i < s; i++) {
            a[i] = at(i);
        }
        return a;
    }

    /**
     * Returns an array of the elements, in order: a, if it has room (followed by null if it has more),
     * else a new array of the runtime type of a.
     *
     * @param <T> the component type of the array
     * @param a the array
     * @return the array of the elements
     * @throws ArrayStoreException if an element is not an instance of the component type of a
     * @throws NullPointerException if a is null
     * @throws IllegalStateException if this list was replaced
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        int s = liveSize();
        T[] r = a.length >= s ? a : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), s);
        for (int i = 0; i < s; i++) {
            r[i] = (T) at(i);
        }
        if (r.length > s) {
            r[s] = null;
        }
        return r;
    }

    /**
     * Performs an action on each element, in order.
     *
     * @param action the action
     * @throws NullPointerException if the action is null
     * @throws ConcurrentModificationException if the action changes the size of the list
     * @throws IllegalStateException if this list was replaced
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        int s = liveSize();
        for (int i = 0; i < s && size == s; i++) {
            action.accept(at(i));
        }
        if (size != s) {
            throw size < 0 ? replaced() : new ConcurrentModificationException();
        }
    }

    /**
     * {@return an iterator over the elements, in order}
     * @throws IllegalStateException if this list was replaced
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr(liveSize());
    }

    private final class Itr implements Iterator<E> {
        private int cursor;
        private int lastRet = -1;
        private int expectedSize;

        Itr(int size) {
            expectedSize = size;
        }

        private void checkSize() {
            int s = size;
            if (s != expectedSize) {
                throw s < 0 ? replaced() : new ConcurrentModificationException();
            }
        }

        @Override
        public boolean hasNext() {
            // as ArrayList's: a changed size (REPLACED included) makes the next call of next() throw
            return cursor != size;
        }

        @Override
        public E next() {
            checkSize();
            int i = cursor;
            if (i >= expectedSize) {
                throw new NoSuchElementException();
            }
            cursor = i + 1;
            return at(lastRet = i);
        }

        @Override
        public void remove() {
            if (lastRet < 0) {
                throw new IllegalStateException();
            }
            checkSize();
            FastArrayList.this.remove(lastRet);
            cursor = lastRet;
            lastRet = -1;
            expectedSize--;
        }
    }

    // ---- List view ---------------------------------------------------------------------------

    /**
     * Returns a {@link List} view of this list. Changes through the view that need a larger list
     * make the view use the larger list from then on (this list is then replaced, and throws); the
     * view throws {@link IllegalStateException} once the list it uses was replaced by a change made
     * without the view.
     *
     * @return a List view
     * @throws IllegalStateException if this list was replaced
     */
    public List<E> asList() {
        liveSize();
        return new ListView<>(this);
    }

    private static final class ListView<E> extends AbstractList<E> implements RandomAccess {
        private FastArrayList<E> list;

        ListView(FastArrayList<E> list) {
            this.list = list;
        }

        @Override public E get(int index)               { return list.get(index); }
        @Override public E set(int index, E element)    { return list.set(index, element); }
        @Override public int size()                     { return list.size(); }
        @Override public int indexOf(Object o)          { return list.indexOf(o); }
        @Override public boolean contains(Object o)     { return list.contains(o); }
        @Override public Object[] toArray()             { return list.toArray(); }
        @Override public <T> T[] toArray(T[] a)         { return list.toArray(a); }

        @Override
        public boolean add(E e) {
            list = list.add(e);
            modCount++;
            return true;
        }

        @Override
        public void add(int index, E element) {
            list = list.add(index, element);
            modCount++;
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            int s = list.size();
            list = list.addAll(c);
            modCount++;
            return list.size() != s;
        }

        @Override
        public E remove(int index) {
            E e = list.remove(index);
            modCount++;
            return e;
        }

        @Override
        public void clear() {
            list.clear();
            modCount++;
        }
    }

    // ---- Object --------------------------------------------------------------------------------

    /**
     * Compares with another FastArrayList or {@link List}: equal if both have equal elements in the
     * same order (as {@link List#equals}).
     *
     * @param o the object to compare with
     * @return whether o is a FastArrayList or List with equal elements in the same order
     * @throws IllegalStateException if this list was replaced
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        int s = liveSize();
        if (o instanceof FastArrayList<?> other) {
            if (other.liveSize() != s) {
                return false;
            }
            for (int i = 0; i < s; i++) {
                if (!Objects.equals(at(i), other.at(i))) {
                    return false;
                }
            }
            return true;
        }
        if (!(o instanceof List<?> list)) {
            return false;
        }
        Iterator<?> it = list.iterator();
        for (int i = 0; i < s; i++) {
            if (!it.hasNext() || !Objects.equals(at(i), it.next())) {
                return false;
            }
        }
        return !it.hasNext();
    }

    /**
     * {@return the hash code of {@link List#hashCode} for the elements}
     * @throws IllegalStateException if this list was replaced
     */
    @Override
    public int hashCode() {
        int s = liveSize();
        int h = 1;
        for (int i = 0; i < s; i++) {
            h = 31 * h + Objects.hashCode(at(i));
        }
        return h;
    }

    /**
     * {@return the elements in brackets, separated by ", "}
     */
    @Override
    public String toString() {
        int s = size;
        if (s < 0) {
            return "[replaced FastArrayList]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < s; i++) {
            Object e = at(i);
            j.add(e == this ? "(this FastArrayList)" : String.valueOf(e));
        }
        return j.toString();
    }

    // ---- serialization ---------------------------------------------------------------------------

    /** Written in place of the list: the VM allocates lists, deserialization cannot. */
    @Serial
    private Object writeReplace() {
        return new SerialForm(toArray());
    }

    @Serial
    private void readObject(ObjectInputStream in) throws InvalidObjectException {
        throw new InvalidObjectException("FastArrayList is serialized through its serial form");
    }

    private static final class SerialForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 4708421592383040212L;
        /** The elements, in order. */
        @SuppressWarnings("serial") // Conditionally serializable
        private final Object[] elements;

        SerialForm(Object[] elements) {
            this.elements = elements;
        }

        @Serial
        private Object readResolve() {
            return copyOf(Arrays.asList(elements));
        }
    }
}
