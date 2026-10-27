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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

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
 * <p>A {@link List}: every change works in place. The capacity of a list object is fixed when it
 * is allocated, and is at least 1. When a change needs another capacity (an addition to a full
 * list, {@link #ensureCapacity(int)}, {@link #trimToSize()}), the elements move to a new list object
 * of that capacity, and the list forwards to it: the list keeps its identity, every reference to it
 * stays valid, and it reaches its elements through one more reference. The young collections of
 * the Serial and Parallel collectors fold a young list that forwards into the list object that holds
 * its elements, which then is the list (same identity, identity hash included): its elements are
 * one reference away again.
 *
 * <p>There is no modification count. Iterators, list iterators and sublists fail fast: they throw
 * {@link ConcurrentModificationException} when the size changed after they were created, other
 * than through them, or the elements moved. Changes that keep the size and the place of the
 * elements ({@link #set}, {@link #sort}, {@link #replaceAll}, a removal followed by an addition) are
 * not detected.
 *
 * <p>The class is not synchronized. Unsynchronized concurrent use may lose updates or throw, but
 * never reads or writes outside a list object.
 *
 * <p>A non-standard class of this runtime.
 *
 * @param <E> the type of the elements
 */
public final class FastArrayList<E> implements List<E>, RandomAccess, Serializable {
    @Serial
    private static final long serialVersionUID = -2393474218547326715L;

    private static final Unsafe U = Unsafe.getUnsafe();
    /** log2 of the size of a heap reference. */
    private static final int REF_SHIFT = Unsafe.ARRAY_OBJECT_INDEX_SCALE == 4 ? 2 : 3;
    /** The capacity of a larger list that replaces a list of smaller capacity, at least. */
    private static final int DEFAULT_CAPACITY = 10;
    /**
     * The size of a list that forwards to another list object, its storage (fwd); a young collection
     * may fold it into the storage (InstanceSwissTableBufferKlass::fast_list_forwarded).
     */
    private static final int FORWARDED = -1;
    /**
     * The size of a storage that the elements left: it forwards to their new storage (fwd), so that
     * its iterators fail, and is never folded, so that they still fail after a collection
     * (InstanceSwissTableBufferKlass::fast_list_retired).
     */
    private static final int RETIRED = -2;

    /** Byte offset of element 0. */
    private static final long DATA_BASE;
    /** The largest capacity a list can have. */
    private static final int MAX_CAPACITY;
    /** Byte offset of the fwd field. */
    private static final long FWD;
    /** Byte offset of the cap field. */
    private static final long CAP;
    /**
     * Whether cap starts an aligned 8-byte word, which capWord0 reads: with compressed class
     * pointers. Then cap is read only as that word, so that it is not loaded on its own next to size
     * (see the VM layout comment).
     */
    private static final boolean CAP_WORD;
    /**
     * Whether fwd is the other half of that word: with compressed oops too (cap at 16, fwd at 20).
     * Then the word tells whether the list forwards as well, and fwd is loaded on its own only from
     * a list that forwards, so that it is not loaded next to element 0 of a list that has elements.
     */
    private static final boolean FWD_IN_WORD;
    private static final boolean BIG_ENDIAN = U.isBigEndian();

    static {
        registerNatives();
        DATA_BASE = dataBase();
        MAX_CAPACITY = maxCapacity();
        FWD = U.objectFieldOffset(FastArrayList.class, "fwd");
        CAP = U.objectFieldOffset(FastArrayList.class, "cap");
        CAP_WORD = (CAP & 7) == 0;
        FWD_IN_WORD = CAP_WORD && FWD == CAP + 4;
    }

    /*
     * Instance state. The VM lays the elements out after these fields (InstanceSwissTableBufferKlass):
     *   size   number of elements; or FORWARDED: the elements are in the list object fwd, the
     *          storage of this list, whose size is the number of elements. When the elements move
     *          again, the storage they leave forwards to the new one as well (RETIRED, so that its
     *          iterators fail), and this list to the new one directly. A young collection of Serial
     *          or Parallel may fold a list that forwards: every reference to it then refers to (the
     *          copy of) its storage, which is the list from then on (FastArrayListFolding in the VM).
     *   cap    the capacity, written by the VM at allocation and never changed. Read with fwd as one
     *          8-byte word where they fill one (capAndForward), so that no two adjacent hot fields
     *          are loaded with the same size (see the VM layout comment); not final, as the word
     *          covers fwd, which changes
     *   fwd    the storage of a list that forwards, else null. A field rather than slot 0: element
     *          stores and slot 0 share one C2 alias class, so that every element store would make C2
     *          load slot 0, and the size of the storage, again (design B P0: an add to a list that
     *          forwards took 3.4 times ArrayList's cycles); and slot 0 may hold an element that a
     *          racing change wrote, so that a list read from it would need a class check on every
     *          access (slot 0 with a C2 load of its own: grow16 1.09 and a forwarded getm-m4k 1.09
     *          times ArrayList's cycles, against 0.93 and 1.00 here). With cap an int, fwd takes the
     *          4 bytes after it, and the elements start at 24 (with compressed oops and class
     *          pointers), as without fwd.
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
    private transient int cap;
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
        Preconditions.checkFromIndexSize(srcPos, n, capOf(src), Preconditions.AIOOBE_FORMATTER);
        Preconditions.checkFromIndexSize(dstPos, n, capOf(dst), Preconditions.AIOOBE_FORMATTER);
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
        Preconditions.checkFromIndexSize(0, n, capOf(src), Preconditions.AIOOBE_FORMATTER);
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
     * The 8 bytes at cap of l (CAP_WORD): cap and, with FWD_IN_WORD, fwd. C2 compiles it to a plain
     * 8-byte load on the alias class of cap, which only the allocation writes, so that the stores of
     * sizes and elements do not make C2 load it again; forwardTo writes fwd between two
     * forwardFence0. Without a control input, so that GVN commons the loads of one list on one
     * memory state (clear then add: one load). Unsafe.getLong itself would come with barriers in C2
     * (an access that covers two fields).
     */
    @IntrinsicCandidate
    private static long capWord0(FastArrayList<?> l) {
        return U.getLong(l, CAP);
    }

    /** For C2, a barrier to all loads and stores (MemBarCPUOrder); no instruction, nothing here. */
    @IntrinsicCandidate
    private static void forwardFence0() {
    }

    /**
     * The capacity of l in the low half ((int) of the value), and a high half that is not 0 exactly
     * when l forwards (fwd is not null): with FWD_IN_WORD one 8-byte load.
     */
    @ForceInline
    private static long capAndForward(FastArrayList<?> l) {
        if (FWD_IN_WORD) {
            long w = capWord0(l);
            return BIG_ENDIAN ? Long.rotateLeft(w, 32) : w;
        }
        long c = !CAP_WORD ? l.cap & 0xFFFFFFFFL
                           : BIG_ENDIAN ? capWord0(l) >>> 32 : capWord0(l) & 0xFFFFFFFFL;
        return l.fwd != null ? c | (1L << 32) : c;
    }

    /** Of a value of capAndForward: whether the list forwards. */
    @ForceInline
    private static boolean forwardsIn(long cw) {
        return (cw >>> 32) != 0;
    }

    /** The capacity of l: the low half of capAndForward. */
    @ForceInline
    private static int capOf(FastArrayList<?> l) {
        return (int) capAndForward(l);
    }

    /**
     * After a read of a negative size of this list: its storage. A plain read: an acquire read of the
     * size (ldar) on every access of a list that forwards keeps C2 from moving the loads of the list
     * out of loops and costs more than the access (P0: get 1.30 times ArrayList's cycles). Without it,
     * a thread that races with a move may read fwd before the size that marks the move: null, or a
     * storage that the elements left since.
     */
    @ForceInline
    private FastArrayList<E> forwardee() {
        FastArrayList<E> t = fwd;
        if (t == null) {
            // This list does not forward (any more): a young collection folded the list object that
            // forwarded into its storage, which this object is, after the caller had tested the
            // forwarding state (compiled code may keep that test in a register across a safepoint);
            // or a racing move had written the size before fwd. The list itself holds the elements,
            // or its negative size makes the caller throw. Taken only after a fold or in a race:
            // with a profile that never took it, C2 makes it an uncommon trap, which it can turn
            // into an implicit null check of the access that follows.
            t = this;
        }
        return t;
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
     * which the elements left, forwards to n too and becomes RETIRED, so that its iterators fail.
     * Plain stores: readers do not read fwd with acquire (forwardee), so release stores here would
     * not order anything for a racing reader, and on Kunpeng 920 a release store after the copy
     * waits for its stores (as a store-store barrier after a copy does; see copyOfSlots). A racing
     * reader may see fwd before the size, or n before its contents; n was cleared, and its capacity
     * written, before it could be seen at all (the allocation's own barrier), so it reads null
     * elements or a smaller size, or throws, but stays inside n.
     * The forwardFence0 before and after these stores keep the loads of capWord0, which C2 does on
     * the alias class of cap, not of fwd, before or after them, as in the program.
     */
    @ForceInline
    private void forwardTo(FastArrayList<E> t, int s, FastArrayList<E> n) {
        forwardFence0();
        if (t == this) {
            U.putReference(this, FWD, n);
            size = FORWARDED;
            for (int i = 0; i < s; i++) {
                put(i, null);
            }
        } else {
            U.putReference(this, FWD, n);
            U.putReference(t, FWD, n);
            t.size = RETIRED;
        }
        forwardFence0();
    }

    /**
     * A capacity of at least minCapacity, grown from cap like java.util.ArrayList grows its array: by
     * half, and to at least DEFAULT_CAPACITY from a smaller capacity (as ArrayList's first growth).
     */
    @ForceInline
    private static int grownCapacity(int cap, int minCapacity) {
        int old = cap;
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
    @Override
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
        return size >= 0 ? capOf(this) : capOf(forwardee());
    }

    /**
     * {@return whether this list has no elements}
     */
    @Override
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
    @Override
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
    @Override
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
    @Override
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
     * {@return the index of the last element equal to o, or -1}
     * @param o the element to look for
     */
    @Override
    public int lastIndexOf(Object o) {
        FastArrayList<E> t = storage();
        for (int i = sizeOf(t) - 1; i >= 0; i--) {
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
    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    // ---- changes that may move the elements --------------------------------------------------

    /**
     * Appends an element. If the list is full, its elements move to a larger list object first.
     *
     * @param e the element
     * @return true
     */
    @Override
    public boolean add(E e) {
        // Forwarding is tested on fwd (in the word of cap, capAndForward), not on the size: every add
        // writes a size (of this list or of its storage), and both are the one field size for C2,
        // which would therefore load the size of this list again and test it at every add of a loop;
        // no add writes fwd or cap, so C2 takes the test out of the loop (P1: an add to a list that
        // forwards took 1.03 times ArrayList's cycles, 5 instructions more than an add to a list that
        // does not).
        long cw = capAndForward(this);
        if (forwardsIn(cw)) {
            forwardee().addHere(e, this);
            return true;
        }
        int s = size;
        // one unsigned 32-bit compare against the low half of the word (cap): a long compare of the
        // size zero-extended took a sign extension and a mask more per add, beside the sign extension
        // that the slot address takes (grow16: 3 instructions per add more than F7)
        if (!inBounds(s, (int) cw)) {           // full, or a negative size read while a racing move
            addToLarger(e);                     // has not written fwd yet
            return true;
        }
        put(s, e);
        size = s + 1;
        return true;
    }

    /**
     * add(E) on the storage of list (inlined: until a collection, every add of a list that forwards
     * comes here); growth of list if the storage is full.
     */
    @ForceInline
    private void addHere(E e, FastArrayList<E> list) {
        int s = size;
        if (inBounds(s, (int) capAndForward(this))) {         // as in add(E)
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
        int c = capOf(t);
        if (s < c) {                            // a racing change made room
            t.put(s, e);
            t.size = s + 1;
            return;
        }
        FastArrayList<E> n = copyOfSlots(t, s, grownCapacity(c, s + 1));
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
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size()]}
     */
    @Override
    public void add(int index, E element) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Objects.checkIndex(index, s + 1);
        int c = capOf(t);
        if (s < c) {
            copySlots(t, index, t, index + 1, s - index);
            t.put(index, element);
            t.size = s + 1;
        } else {
            FastArrayList<E> n = copyOfSlots(t, index, grownCapacity(c, s + 1));
            copySlots(t, index, n, index + 1, s - index);
            n.put(index, element);
            n.size = s + 1;
            forwardTo(t, s, n);
        }
    }

    /**
     * Appends the elements of a collection, in the order of its iterator. If they do not fit, the
     * elements of the list move to a larger list object.
     *
     * @param c the collection
     * @return whether the collection had an element
     * @throws NullPointerException if the collection is null
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        Object[] a = c.toArray();
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        if (a.length == 0) {
            return false;
        }
        int capT = capOf(t);
        if (a.length <= capT - s) {
            for (int i = 0; i < a.length; i++) {
                t.put(s + i, a[i]);
            }
            t.size = s + a.length;
        } else {
            if (a.length > MAX_CAPACITY - s) {
                throw new OutOfMemoryError("Required FastArrayList capacity " + ((long) s + a.length) + " is too large");
            }
            FastArrayList<E> n = copyOfSlots(t, s, grownCapacity(capT, s + a.length));
            for (int i = 0; i < a.length; i++) {
                n.put(s + i, a[i]);
            }
            n.size = s + a.length;
            forwardTo(t, s, n);
        }
        return true;
    }

    /**
     * Inserts the elements of a collection at an index, in the order of its iterator, moving the
     * elements from that index up. If they do not fit, the elements of the list move to a larger list
     * object.
     *
     * @param index the index, in {@code [0, size()]}
     * @param c the collection
     * @return whether the collection had an element
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size()]}
     * @throws NullPointerException if the collection is null
     */
    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        Object[] a = c.toArray();
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Objects.checkIndex(index, s + 1);
        int n = a.length;
        if (n == 0) {
            return false;
        }
        int capT = capOf(t);
        if (n <= capT - s) {
            copySlots(t, index, t, index + n, s - index);
            for (int i = 0; i < n; i++) {
                t.put(index + i, a[i]);
            }
            t.size = s + n;
        } else {
            if (n > MAX_CAPACITY - s) {
                throw new OutOfMemoryError("Required FastArrayList capacity " + ((long) s + n) + " is too large");
            }
            FastArrayList<E> m = copyOfSlots(t, index, grownCapacity(capT, s + n));
            copySlots(t, index, m, index + n, s - index);
            for (int i = 0; i < n; i++) {
                m.put(index + i, a[i]);
            }
            m.size = s + n;
            forwardTo(t, s, m);
        }
        return true;
    }

    /**
     * Makes the capacity at least minCapacity: if it is less, the elements move to a larger list
     * object.
     *
     * @param minCapacity the capacity wanted
     */
    public void ensureCapacity(int minCapacity) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        int c = capOf(t);
        if (minCapacity > c) {
            forwardTo(t, s, copyOfSlots(t, s, grownCapacity(c, minCapacity)));
        }
    }

    /**
     * Makes the capacity the size (at least 1): if it is not, the elements move to a list object of
     * that capacity. Until a collection folds the list into it, the list object of the old capacity
     * stays too.
     */
    public void trimToSize() {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        int c = Math.max(s, 1);
        if (c != capOf(t)) {
            forwardTo(t, s, copyOfSlots(t, s, c));
        }
    }

    // ---- changes in place --------------------------------------------------------------------

    /**
     * Removes the element at an index, moving the elements after it one down.
     *
     * @param index the index
     * @return the element that was removed
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size())}
     */
    @Override
    public E remove(int index) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);                         // read once: every slot written below is under s
        if (!inBounds(index, s)) {
            throw outOfBounds(index, s);
        }
        E old = t.at(index);
        fastRemove(t, s, index);
        return old;
    }

    /** Removes slot i of storage t, which has s elements (i in [0, s)). */
    private static void fastRemove(FastArrayList<?> t, int s, int i) {
        copySlots(t, i + 1, t, i, s - i - 1);
        t.put(s - 1, null);
        t.size = s - 1;
    }

    /**
     * Removes the first element equal to o, if there is one.
     *
     * @param o the element to remove
     * @return whether the list had such an element
     */
    @Override
    public boolean remove(Object o) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        for (int i = 0; i < s; i++) {
            if (Objects.equals(o, t.at(i))) {
                fastRemove(t, s, i);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the last element.
     *
     * @return the element that was removed
     * @throws NoSuchElementException if this list is empty
     */
    @Override
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
    @Override
    public void clear() {
        if (forwardsIn(capAndForward(this))) {  // tested on fwd, as in add(E)
            forwardee().clearHere();
            return;
        }
        int s = sizeOf(this);
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

    /**
     * {@return whether this list has an element equal to every element of a collection}
     * @param c the collection
     * @throws NullPointerException if the collection is null
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!contains(e)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the elements that a collection contains.
     *
     * @param c the collection
     * @return whether an element was removed
     * @throws NullPointerException if the collection is null
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        return batchRemove(c, false);
    }

    /**
     * Removes the elements that a collection does not contain.
     *
     * @param c the collection
     * @return whether an element was removed
     * @throws NullPointerException if the collection is null
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        return batchRemove(c, true);
    }

    /**
     * Keeps the elements e with c.contains(e) == keep, in order. If c.contains throws, the elements
     * it did not look at stay, as with ArrayList.
     */
    private boolean batchRemove(Collection<?> c, boolean keep) {
        Objects.requireNonNull(c);
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        int r = 0, w = 0;
        try {
            for (; r < s; r++) {
                E e = t.at(r);
                if (c.contains(e) == keep) {
                    t.put(w++, e);
                }
            }
        } finally {
            if (r != s) {
                copySlots(t, r, t, w, s - r);
                w += s - r;
            }
            if (w != s) {
                for (int i = w; i < s; i++) {
                    t.put(i, null);
                }
                t.size = w;
            }
        }
        return w != s;
    }

    /**
     * Removes the elements a predicate holds for. The predicate sees every element before any is
     * removed; if it throws, the list does not change.
     *
     * @param filter the predicate
     * @return whether an element was removed
     * @throws NullPointerException if the predicate is null
     * @throws ConcurrentModificationException if the predicate changes the size of the list or makes
     *         its elements move
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        long[] dead = null;
        for (int i = 0; i < s; i++) {
            if (filter.test(t.at(i))) {
                if (dead == null) {
                    dead = new long[(s + 63) >>> 6];
                }
                dead[i >>> 6] |= 1L << i;
            }
        }
        if (t.size != s) {                         // a move marks t FORWARDED or RETIRED
            throw new ConcurrentModificationException();
        }
        if (dead == null) {
            return false;
        }
        int w = 0;
        for (int r = 0; r < s; r++) {
            if ((dead[r >>> 6] & (1L << r)) == 0) {
                t.put(w++, t.at(r));
            }
        }
        for (int i = w; i < s; i++) {
            t.put(i, null);
        }
        t.size = w;
        return true;
    }

    /**
     * Replaces each element with the result of an operator on it.
     *
     * @param operator the operator
     * @throws NullPointerException if the operator is null
     * @throws ConcurrentModificationException if the operator changes the size of the list or makes
     *         its elements move
     */
    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator);
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        for (int i = 0; i < s && t.size == s; i++) {
            t.put(i, operator.apply(t.at(i)));
        }
        if (t.size != s) {
            throw new ConcurrentModificationException();
        }
    }

    /**
     * Sorts the elements, by a comparator or, for null, by their natural order (stable).
     *
     * @param c the comparator, or null
     * @throws ClassCastException if elements are not mutually comparable
     * @throws ConcurrentModificationException if the comparator changes the size of the list or
     *         makes its elements move
     */
    @Override
    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Object[] a = new Object[s];
        for (int i = 0; i < s; i++) {
            a[i] = t.at(i);
        }
        Arrays.sort((E[]) a, c);
        if (t.size != s) {
            throw new ConcurrentModificationException();
        }
        for (int i = 0; i < s; i++) {
            t.put(i, a[i]);
        }
    }

    /** Removes the elements in [fromIndex, toIndex), moving the later ones down (for sublists). */
    private void removeRange(int fromIndex, int toIndex) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Objects.checkFromToIndex(fromIndex, toIndex, s);
        copySlots(t, toIndex, t, fromIndex, s - toIndex);
        int n = s - (toIndex - fromIndex);
        for (int i = n; i < s; i++) {
            t.put(i, null);
        }
        t.size = n;
    }

    // ---- bulk reads --------------------------------------------------------------------------

    /**
     * {@return an array of the elements, in order}
     */
    @Override
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
    @Override
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
        if (t.size != s) {                         // a move marks t FORWARDED or RETIRED
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
     * {@return a list iterator over the elements, in order, from the first}
     */
    @Override
    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    /**
     * Returns a list iterator over the elements, in order, from an index.
     *
     * @param index the index of the element that next() returns first, in {@code [0, size()]}
     * @return a list iterator
     * @throws IndexOutOfBoundsException if the index is out of {@code [0, size()]}
     */
    @Override
    public ListIterator<E> listIterator(int index) {
        FastArrayList<E> t = storage();
        int s = sizeOf(t);
        Objects.checkIndex(index, s + 1);
        return new ListItr(t, s, index);
    }

    /**
     * An iterator over the list object that holds the elements when it is made: this list, or its
     * storage. It fails when the size of that object changes, which a move of the elements does as
     * well (forwardTo marks the object they leave FORWARDED or RETIRED).
     */
    private class Itr implements Iterator<E> {
        FastArrayList<E> list;
        int cursor;
        int lastRet = -1;
        int expectedSize;

        Itr(FastArrayList<E> list, int size) {
            this.list = list;
            expectedSize = size;
        }

        final void checkSize() {
            if (list.size != expectedSize) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public boolean hasNext() {
            // as ArrayList's: a changed size (FORWARDED or RETIRED included) makes next() throw
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

    /**
     * A list iterator: an Itr that can also go back, set and add. An add may move the elements; the
     * iterator then iterates the list object they moved to.
     */
    private final class ListItr extends Itr implements ListIterator<E> {
        ListItr(FastArrayList<E> list, int size, int index) {
            super(list, size);
            cursor = index;
        }

        @Override
        public boolean hasPrevious() {
            return cursor != 0;
        }

        @Override
        public int nextIndex() {
            return cursor;
        }

        @Override
        public int previousIndex() {
            return cursor - 1;
        }

        @Override
        public E previous() {
            checkSize();
            int i = cursor - 1;
            if (i < 0) {
                throw new NoSuchElementException();
            }
            if (i >= expectedSize) {
                throw new ConcurrentModificationException();
            }
            cursor = i;
            return list.at(lastRet = i);
        }

        @Override
        public void set(E e) {
            if (lastRet < 0) {
                throw new IllegalStateException();
            }
            checkSize();
            FastArrayList.this.set(lastRet, e);
        }

        @Override
        public void add(E e) {
            checkSize();
            int i = cursor;
            FastArrayList.this.add(i, e);
            cursor = i + 1;
            lastRet = -1;
            FastArrayList<E> t = storage();
            list = t;
            expectedSize = sizeOf(t);
        }
    }

    // ---- sublists ----------------------------------------------------------------------------

    /**
     * Returns a view of the elements in [fromIndex, toIndex). Changes through the view show in this
     * list and the other way round; a change of the size of this list other than through the view
     * makes the view fail (ConcurrentModificationException).
     *
     * @param fromIndex the first index, included
     * @param toIndex the last index, excluded
     * @return the view
     * @throws IndexOutOfBoundsException if fromIndex is negative, or toIndex larger than the size
     * @throws IllegalArgumentException if fromIndex is larger than toIndex
     */
    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        AbstractList.subListRangeCheck(fromIndex, toIndex, size());
        return new SubList<>(this, null, fromIndex, toIndex);
    }

    /**
     * A view of [offset, offset + size) of root, or of a part of the sublist parent. An AbstractList
     * (its modification count is the view's own, for its iterators); changes of root not through the
     * view show as a size of root other than the one the view last saw.
     */
    private static final class SubList<E> extends AbstractList<E> implements RandomAccess {
        private final FastArrayList<E> root;
        private final SubList<E> parent;
        private final int offset;
        private int size;
        private int expectedRootSize;

        SubList(FastArrayList<E> root, SubList<E> parent, int offset, int toIndex) {
            this.root = root;
            this.parent = parent;
            this.offset = offset;
            this.size = toIndex - offset;
            this.expectedRootSize = root.size();
        }

        private void checkForComodification() {
            if (root.size() != expectedRootSize) {
                throw new ConcurrentModificationException();
            }
        }

        private void rangeCheckForAdd(int index) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
        }

        /** After a change through this view of delta elements: this view and the ones it is part of. */
        private void updateSize(int delta) {
            int rs = root.size();
            for (SubList<E> v = this; v != null; v = v.parent) {
                v.size += delta;
                v.expectedRootSize = rs;
                v.modCount++;
            }
        }

        @Override
        public E get(int index) {
            Objects.checkIndex(index, size);
            checkForComodification();
            return root.get(offset + index);
        }

        @Override
        public E set(int index, E element) {
            Objects.checkIndex(index, size);
            checkForComodification();
            return root.set(offset + index, element);
        }

        @Override
        public int size() {
            checkForComodification();
            return size;
        }

        @Override
        public void add(int index, E element) {
            rangeCheckForAdd(index);
            checkForComodification();
            root.add(offset + index, element);
            updateSize(1);
        }

        @Override
        public E remove(int index) {
            Objects.checkIndex(index, size);
            checkForComodification();
            E e = root.remove(offset + index);
            updateSize(-1);
            return e;
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            checkForComodification();
            root.removeRange(offset + fromIndex, offset + toIndex);
            updateSize(fromIndex - toIndex);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            return addAll(size, c);
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> c) {
            rangeCheckForAdd(index);
            checkForComodification();
            int before = root.size();
            boolean changed = root.addAll(offset + index, c);
            updateSize(root.size() - before);
            return changed;
        }

        @Override
        public List<E> subList(int fromIndex, int toIndex) {
            subListRangeCheck(fromIndex, toIndex, size);
            return new SubList<>(root, this, offset + fromIndex, offset + toIndex);
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
            j.add(e == this ? "(this Collection)" : String.valueOf(e));
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
