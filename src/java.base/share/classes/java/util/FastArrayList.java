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
import java.util.function.Consumer;

import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import jdk.internal.util.Preconditions;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * A list that is its own buffer: one object holds the size, the capacity and the elements, so an
 * element is one reference away from the list instead of two (a list, then its array).
 *
 * <p>The capacity of a list object is fixed when it is allocated, and is at least 1. When a change
 * needs another capacity ({@link #add(Object)}, {@link #add(int, Object)}, {@link #addAll(Collection)},
 * {@link #ensureCapacity(int)} need more room, {@link #trimToSize()} less), the elements move to a
 * new list object of that capacity, and the list forwards to it: the list keeps its identity, every
 * reference to it stays valid, and it reaches its elements through one more reference. These
 * methods return this list.
 *
 * <p>This class is not a {@link List}. {@link #asList()} returns a {@code List} view.
 *
 * <p>Iterators fail fast: they throw {@link ConcurrentModificationException} when the size changed
 * after they were created, other than through the iterator itself, or the elements moved. Changes
 * that keep the size and the place of the elements ({@link #set}, a removal followed by an addition)
 * are not detected.
 *
 * <p>The class is not synchronized. Unsynchronized concurrent use may lose updates or throw, but
 * never reads or writes outside a list object.
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
    /** The size of a list that forwards to another list object, its storage (fwd). */
    private static final int FORWARDED = -1;

    /** Byte offset of element 0. */
    private static final long DATA_BASE;
    /** The largest capacity a list can have. */
    private static final int MAX_CAPACITY;
    /** Byte offset of the fwd field. */
    private static final long FWD;

    static {
        registerNatives();
        DATA_BASE = dataBase();
        MAX_CAPACITY = maxCapacity();
        FWD = U.objectFieldOffset(FastArrayList.class, "fwd");
    }

    /*
     * Instance state. The VM lays the elements out after these fields (InstanceSwissTableBufferKlass):
     *   size   number of elements; or FORWARDED: the elements are in the list object fwd, the
     *          storage of this list, whose size is the number of elements. When the elements move
     *          again, the storage they leave forwards to the new one as well (so that its iterators
     *          fail), and this list to the new one directly.
     *   cap    the capacity, written by the VM at allocation and never changed; a long, so that no
     *          two adjacent hot fields have the same size (see the VM layout comment)
     *   fwd    the storage of a list that forwards, else null. A field rather than slot 0: element
     *          stores and slot 0 share one C2 alias class, so that every element store would make C2
     *          load slot 0, and the size of the storage, again (design B P0: an add to a list that
     *          forwards took 3.4 times ArrayList's cycles). It moves the elements from offset 24 to 32.
     *          Written only through Unsafe, like the slots and SwissTableBuffer.aux: the collectors
     *          scan objects of this kind only within dirty cards (oopDesc::is_marked_precisely), and a
     *          putfield from compiled code marks the card of the object start, which need not hold
     *          fwd (P0: lists lost their storage in collections after a putfield of fwd).
     * The elements 0..size-1 are at DATA_BASE + (i << REF_SHIFT); the slots from size to cap are null.
     * A list that forwards has no elements in its own slots. Only this list refers to its storage, and
     * the storage is never handed out: the list keeps its identity.
     * Memory safety does not depend on the size being right: every element access checks its index
     * against a size that is at least 0 and at most the cap of the list object accessed, which never
     * changes. Each method reads the size of the list object it accesses once and bounds all its slot
     * accesses by that value (or by a snapshot of an iterator), so that a racing change cannot make it
     * access a slot outside [0, cap).
     */
    private transient int size;
    private final transient long cap;
    private transient FastArrayList<E> fwd;

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

    /**
     * A list of the given capacity with the first n elements of src, and size n; src is unchanged.
     * Both ranges are checked against the capacities, as by copySlots.
     * The new list is cleared when allocated, before the store-store barrier that orders its
     * initialization before its publication, and the copy follows. Copying into the uncleared list
     * and clearing only the rest, as C2 does for Arrays.copyOf, needs the barrier after the copy;
     * on Kunpeng 920 that costs more than the clearing it saves (grow16 of ListProbe: 16.5 against
     * 14.8 cycles per add).
     */
    @ForceInline
    @SuppressWarnings("unchecked")
    private static <T> FastArrayList<T> copyOfSlots(FastArrayList<?> src, int n, int capacity) {
        Preconditions.checkFromIndexSize(0, n, (int) src.cap, Preconditions.AIOOBE_FORMATTER);
        Preconditions.checkFromIndexSize(0, n, capacity, Preconditions.AIOOBE_FORMATTER);
        FastArrayList<T> l = (FastArrayList<T>) allocate(capacity);
        copySlots0(src, 0, l, 0, n);
        l.size = n;
        return l;
    }

    /** index in [0, size) for a size of at least 0: one unsigned compare (CmpINode::Ideal makes it CmpU). */
    @ForceInline
    private static boolean inBounds(int index, int size) {
        return index + Integer.MIN_VALUE < size + Integer.MIN_VALUE;
    }

    private static IndexOutOfBoundsException outOfBounds(int index, int size) {
        return new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
    }

    // ---- forwarding --------------------------------------------------------------------------

    /**
     * After a read of a negative size of this list: its storage. A plain read: an acquire read of the
     * size (ldar) on every access of a list that forwards keeps C2 from moving the loads of the list
     * out of loops and costs more than the access (P0: get 1.30 times ArrayList's cycles). Without it,
     * a thread that races with a move may read fwd before the size that marks the move: null, or a
     * storage that the elements left since.
     */
    @ForceInline
    private FastArrayList<E> forwardee() {
        return fwd;
    }

    /** The list object that holds the elements: this one, or its storage. */
    @ForceInline
    private FastArrayList<E> storage() {
        return size >= 0 ? this : forwardee();
    }

    /**
     * The size of a storage (or of this list, not forwarding), read once. The storage of a list does
     * not forward; it does only for a thread that races with a move (it read fwd before the move was
     * complete, and got a storage that the elements left since).
     */
    @ForceInline
    private static int sizeOf(FastArrayList<?> t) {
        int s = t.size;
        if (s < 0) {
            throw new ConcurrentModificationException();
        }
        return s;
    }

    /**
     * Makes this list forward to n, which holds the elements now; they were copied from t, this list
     * or its storage, which had s elements. The first time, fwd gets n and the size becomes FORWARDED,
     * then the slots are cleared, so that they do not keep elements alive; later, fwd gets n, and t,
     * which the elements left, forwards to n too, so that its iterators fail.
     * Plain stores: readers do not read fwd with acquire (forwardee), so release stores here would
     * not order anything for a racing reader, and on Kunpeng 920 a release store after the copy
     * waits for its stores (as a store-store barrier after a copy does; see copyOfSlots). A racing
     * reader may see fwd before the size, or n before its contents; n was cleared, and its capacity
     * written, before it could be seen at all (the allocation's own barrier), so it reads null
     * elements or a smaller size, or throws, but stays inside n.
     */
    @ForceInline
    private void forwardTo(FastArrayList<E> t, int s, FastArrayList<E> n) {
        if (t == this) {
            U.putReference(this, FWD, n);
            size = FORWARDED;
            for (int i = 0; i < s; i++) {
                put(i, null);
            }
        } else {
            U.putReference(this, FWD, n);
            U.putReference(t, FWD, n);
            t.size = FORWARDED;
        }
    }

    /**
     * A capacity of at least minCapacity, grown from cap like java.util.ArrayList grows its array: by
     * half, and to at least DEFAULT_CAPACITY from a smaller capacity (as ArrayList's first growth).
     */
    @ForceInline
    private static int grownCapacity(long cap, int minCapacity) {
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
     */
    @ForceInline
    public int size() {
        int s = size;
        return s >= 0 ? s : sizeOf(forwardee());
    }

    /**
     * {@return the number of elements this list can hold before its elements move}
     */
    @ForceInline
    public int capacity() {
        return size >= 0 ? (int) cap : (int) forwardee().cap;
    }

    /**
     * {@return whether this list has no elements}
     */
    @ForceInline
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Returns the element at an index.
     *
     * @param index the index
     * @return the element
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     */
    public E get(int index) {
        int s = size;
        if (s < 0) {
            return forwardee().getHere(index);
        }
        // checkIndex rather than one unsigned compare: C2's intrinsic (a RangeCheck, and the index cast
        // to [0, s)) gives the code of the explicit-interface version; the compare made get 1.5% slower
        Preconditions.checkIndex(index, s, Preconditions.IOOBE_FORMATTER);
        return at(index);
    }

    /** get(int) on a storage. */
    @ForceInline
    private E getHere(int index) {
        Preconditions.checkIndex(index, sizeOf(this), Preconditions.IOOBE_FORMATTER);
        return at(index);
    }

    /**
     * Replaces the element at an index.
     *
     * @param index the index
     * @param element the new element
     * @return the element that was at the index
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     */
    public E set(int index, E element) {
        int s = size;
        if (s < 0) {
            return forwardee().setHere(index, element);
        }
        Preconditions.checkIndex(index, s, Preconditions.IOOBE_FORMATTER);   // see get(int)
        E old = at(index);
        put(index, element);
        return old;
    }

    /** set(int, E) on a storage. */
    @ForceInline
    private E setHere(int index, E element) {
        Preconditions.checkIndex(index, sizeOf(this), Preconditions.IOOBE_FORMATTER);
        E old = at(index);
        put(index, element);
        return old;
    }

    /**
     * {@return the index of the first element equal to o, or -1}
     * @param o the element to look for
     */
    public int indexOf(Object o) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        for (int i = 0; i < s; i++) {
            if (Objects.equals(o, t.at(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return whether this list has an element equal to o}
     * @param o the element to look for
     */
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    // ---- changes that may move the elements --------------------------------------------------

    /**
     * Appends an element. If the list is full, its elements move to a larger list object first.
     *
     * @param e the element
     * @return this list
     */
    public FastArrayList<E> add(E e) {
        int s = size;
        if (s < 0) {                            // forwarding: the sign test first, so that this path
            forwardee().addHere(e, this);       // skips the comparison with the cap of this list
            return this;
        }
        if (s >= cap) {
            addToLarger(e);
            return this;
        }
        put(s, e);
        size = s + 1;
        return this;
    }

    /**
     * add(E) on the storage of list (inlined: until a collection, every add of a list that forwards
     * comes here); growth of list if the storage is full.
     */
    @ForceInline
    private void addHere(E e, FastArrayList<E> list) {
        int s = size;
        if ((s & 0xFFFFFFFFL) < cap) {
            put(s, e);
            size = s + 1;
            return;
        }
        list.addToLarger(e);
    }

    /** The growth of add(E), out of line so that add(E) stays small enough to inline everywhere. */
    @DontInline
    private void addToLarger(E e) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (s < t.cap) {                        // a racing change made room
            t.put(s, e);
            t.size = s + 1;
            return;
        }
        FastArrayList<E> n = copyOfSlots(t, s, grownCapacity(t.cap, s + 1));
        n.put(s, e);
        n.size = s + 1;
        forwardTo(t, s, n);
    }

    /**
     * Inserts an element at an index, moving the elements from that index one up. If the list is
     * full, its elements move to a larger list object.
     *
     * @param index the index, in {@code [0, size()]}
     * @param element the element
     * @return this list
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size()]}
     */
    public FastArrayList<E> add(int index, E element) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Objects.checkIndex(index, s + 1);
        if (s < t.cap) {
            copySlots(t, index, t, index + 1, s - index);
            t.put(index, element);
            t.size = s + 1;
        } else {
            FastArrayList<E> n = copyOfSlots(t, index, grownCapacity(t.cap, s + 1));
            copySlots(t, index, n, index + 1, s - index);
            n.put(index, element);
            n.size = s + 1;
            forwardTo(t, s, n);
        }
        return this;
    }

    /**
     * Appends the elements of a collection, in the order of its iterator. If they do not fit, the
     * elements of the list move to a larger list object.
     *
     * @param c the collection
     * @return this list
     * @throws NullPointerException if the collection is null
     */
    public FastArrayList<E> addAll(Collection<? extends E> c) {
        Object[] a = c.toArray();
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (a.length == 0) {
            return this;
        }
        if (a.length <= t.cap - s) {
            for (int i = 0; i < a.length; i++) {
                t.put(s + i, a[i]);
            }
            t.size = s + a.length;
        } else {
            if (a.length > MAX_CAPACITY - s) {
                throw new OutOfMemoryError("Required FastArrayList capacity " + ((long) s + a.length) + " is too large");
            }
            FastArrayList<E> n = copyOfSlots(t, s, grownCapacity(t.cap, s + a.length));
            for (int i = 0; i < a.length; i++) {
                n.put(s + i, a[i]);
            }
            n.size = s + a.length;
            forwardTo(t, s, n);
        }
        return this;
    }

    /**
     * Makes the capacity at least minCapacity: if it is less, the elements move to a larger list
     * object.
     *
     * @param minCapacity the capacity wanted
     * @return this list
     */
    public FastArrayList<E> ensureCapacity(int minCapacity) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (minCapacity > t.cap) {
            forwardTo(t, s, copyOfSlots(t, s, grownCapacity(t.cap, minCapacity)));
        }
        return this;
    }

    /**
     * Makes the capacity the size (at least 1): if it is not, the elements move to a list object of
     * that capacity. Until a collection folds the list into it, the list object of the old capacity
     * stays too.
     *
     * @return this list
     */
    public FastArrayList<E> trimToSize() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        int c = Math.max(s, 1);
        if (c != t.cap) {
            forwardTo(t, s, copyOfSlots(t, s, c));
        }
        return this;
    }

    // ---- changes in place --------------------------------------------------------------------

    /**
     * Removes the element at an index, moving the elements after it one down.
     *
     * @param index the index
     * @return the element that was removed
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     */
    public E remove(int index) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);                         // read once: every slot written below is under s
        if (!inBounds(index, s)) {
            throw outOfBounds(index, s);
        }
        E old = t.at(index);
        copySlots(t, index + 1, t, index, s - index - 1);
        t.put(s - 1, null);
        t.size = s - 1;
        return old;
    }

    /**
     * Removes the last element.
     *
     * @return the element that was removed
     * @throws NoSuchElementException if this list is empty
     */
    public E removeLast() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (s == 0) {
            throw new NoSuchElementException();
        }
        E old = t.at(s - 1);
        t.put(s - 1, null);
        t.size = s - 1;
        return old;
    }

    /**
     * Removes all elements. The capacity does not change.
     */
    public void clear() {
        int s = size;
        if (s < 0) {
            forwardee().clearHere();
            return;
        }
        for (int i = 0; i < s; i++) {
            put(i, null);
        }
        size = 0;
    }

    /**
     * clear() on a storage; inlined, so that C2 knows the size 0 of the storage in the code that
     * follows (P0: as a call, the adds after it computed their slots from a size read back).
     */
    @ForceInline
    private void clearHere() {
        int s = sizeOf(this);
        for (int i = 0; i < s; i++) {
            put(i, null);
        }
        size = 0;
    }

    // ---- bulk reads --------------------------------------------------------------------------

    /**
     * {@return an array of the elements, in order}
     */
    public Object[] toArray() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Object[] a = new Object[s];
        for (int i = 0; i < s; i++) {
            a[i] = t.at(i);
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
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        T[] r = a.length >= s ? a : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), s);
        for (int i = 0; i < s; i++) {
            r[i] = (T) t.at(i);
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
     * @throws ConcurrentModificationException if the action changes the size of the list or makes
     *         its elements move
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        for (int i = 0; i < s && t.size == s; i++) {
            action.accept(t.at(i));
        }
        if (t.size != s) {                         // a move marks t FORWARDED
            throw new ConcurrentModificationException();
        }
    }

    /**
     * {@return an iterator over the elements, in order}
     */
    @Override
    public Iterator<E> iterator() {
        FastArrayList<E> t = this;
        int s = size;
        if (s < 0) {
            t = forwardee();
            s = sizeOf(t);
        }
        return new Itr(t, s);
    }

    /**
     * An iterator over the list object that holds the elements when it is made: this list, or its
     * storage. It fails when the size of that object changes, which a move of the elements does as
     * well (forwardTo marks the object they leave FORWARDED).
     */
    private final class Itr implements Iterator<E> {
        private final FastArrayList<E> list;
        private int cursor;
        private int lastRet = -1;
        private int expectedSize;

        Itr(FastArrayList<E> list, int size) {
            this.list = list;
            expectedSize = size;
        }

        private void checkSize() {
            if (list.size != expectedSize) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public boolean hasNext() {
            // as ArrayList's: a changed size (FORWARDED included) makes the next call of next() throw
            return cursor != list.size;
        }

        @Override
        public E next() {
            checkSize();
            int i = cursor;
            if (i >= expectedSize) {
                throw new NoSuchElementException();
            }
            cursor = i + 1;
            return list.at(lastRet = i);
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
     * Returns a {@link List} view of this list: changes through either show in the other.
     *
     * @return a List view
     */
    public List<E> asList() {
        return new ListView<>(this);
    }

    private static final class ListView<E> extends AbstractList<E> implements RandomAccess {
        private final FastArrayList<E> list;

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
            list.add(e);
            modCount++;
            return true;
        }

        @Override
        public void add(int index, E element) {
            list.add(index, element);
            modCount++;
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            int s = list.size();
            list.addAll(c);
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
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (o instanceof FastArrayList<?> other) {
            FastArrayList<?> ot = other.storage();
            if (sizeOf(ot) != s) {
                return false;
            }
            for (int i = 0; i < s; i++) {
                if (!Objects.equals(t.at(i), ot.at(i))) {
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
            if (!it.hasNext() || !Objects.equals(t.at(i), it.next())) {
                return false;
            }
        }
        return !it.hasNext();
    }

    /**
     * {@return the hash code of {@link List#hashCode} for the elements}
     */
    @Override
    public int hashCode() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        int h = 1;
        for (int i = 0; i < s; i++) {
            h = 31 * h + Objects.hashCode(t.at(i));
        }
        return h;
    }

    /**
     * {@return the elements in brackets, separated by ", "}
     */
    @Override
    public String toString() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < s; i++) {
            Object e = t.at(i);
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
