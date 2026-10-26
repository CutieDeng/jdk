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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * Swiss-table representation of {@link HashMap}: what an allocation of exactly {@code HashMap}
 * produces under {@code -XX:+UseSwissTable}; {@code Object.getClass()} reports
 * {@code HashMap}. {@code HashMap} keeps the node-based implementation, which {@link LinkedHashMap}
 * extends. No code of {@code HashMap} runs on a SwissTable: every overridable method is overridden
 * here, and the VM redirects the rest. Code outside HashMap and its subclasses that allocates
 * exactly {@code HashMap} gets a SwissTable and the SwissTable constructor with the same parameters
 * (bytecode in the interpreter and both compilers, JNI {@code NewObject}, core reflection); the
 * constructor handles of {@code java.lang.invoke} do the same, {@code HashMap.newHashMap} binds to
 * {@link #newHashMap}, and serialization runs this class's {@code writeObject}/{@code readObject},
 * which keep HashMap's serial form. The constructors here call {@code super()}, which the VM binds
 * to {@code AbstractMap()}.
 *
 * <p>Storage. The map holds one pointer, {@link #buf}, to a single VM-defined buffer, a
 * {@link SwissTableBuffer}: its control bytes followed by its key/value references, one heap object per
 * table. The capacity C is 0 or a power of two of at least {@code GROUP}; slots form C/8 groups of
 * 8, and a group's 8 control bytes are read as one 8-byte control word. A control byte is
 * {@code EMPTY} (0), {@code DELETED} (1), or {@code 0x80 | h2} for a full slot, so a freshly
 * zeroed buffer is an empty table. The key and value of slot i are references 2i and 2i+1 of the
 * reference region. Every access goes through Unsafe at offsets derived from one table's own
 * immutable capacity, so the reference accesses carry the collector's barriers and stay inside the
 * table even under unsynchronized concurrent use.
 *
 * <p>Hashing and probing. {@code p = hashCode * GOLDEN} in 64 bits; {@code h1 = p >>> 32} picks the
 * first group and {@code h2 = p >>> 57} is the tag. Groups are probed at triangular offsets 0, 1,
 * 3, 6, ..., which visit every group of a power-of-two table. A lookup stops at the first group
 * that has an EMPTY slot; an insert takes the first EMPTY or DELETED slot on the way. At most 7/8
 * of the slots are full or deleted, so every probe meets an EMPTY slot; a probe also ends after one
 * cycle over all groups, which only matters when unsynchronized concurrent writers broke that
 * invariant. An erased slot becomes EMPTY if its group still has an EMPTY slot, else DELETED;
 * tombstones are purged by rehashing at the same capacity. Tables grow 2x.
 *
 * <p>Equal hash codes. Keys with equal {@code hashCode} share one probe sequence and no mixing can
 * separate them, so each operation on such a key compares it with all of them: n such keys cost
 * O(n) per operation and O(n^2) to insert. There is no fallback: node-based {@code HashMap} bounds
 * this with tree bins (JEP 180), this class does not. A table at the largest capacity the VM
 * allocates fills its free slots past the 7/8 limit, then an insert throws OutOfMemoryError.
 *
 * <p>Iteration order is slot order. It differs from node-based {@code HashMap}'s order (the
 * specification makes no ordering promise) but is the same in every run for the same hash codes
 * and the same sequence of operations.
 */
final class SwissTable<K,V> extends HashMap<K,V> {
    @java.io.Serial
    private static final long serialVersionUID = 362498820763181265L;

    private static final Unsafe U = Unsafe.getUnsafe();
    /** Byte offset of the first control byte within a table. */
    private static final int CTRL_BASE = SwissTableBuffer.ctrlBase();
    /** log2 of the size of a heap reference. */
    private static final int REF_SHIFT = Unsafe.ARRAY_OBJECT_INDEX_SCALE == 4 ? 2 : 3;
    /** Size in bytes of a heap reference: the value of a slot follows its key at this distance. */
    private static final int REF_SIZE = 1 << REF_SHIFT;
    /** log2 of the size of a slot, two references. */
    private static final int SLOT_SHIFT = REF_SHIFT + 1;
    private static final boolean BIG_ENDIAN = U.isBigEndian();

    static final int GROUP = 8;
    static final int MIN_CAPACITY = GROUP;
    /**
     * Capacity of the first table of a map created without a size: two groups, room for 14
     * mappings. java.util.HashMap's first table (16 buckets) takes 12 before it resizes, and code
     * written for it builds many short-lived maps of 8 to 12 mappings (request attributes, task
     * parameters). A first table of 8 slots rehashed each of them once; on feedinfo's api-service
     * that rehash cost 3.3 times the node-based resize at the same call sites.
     */
    static final int FIRST_CAPACITY = 2 * GROUP;
    static final int MAX_CAPACITY = SwissTableBuffer.maxCapacity();
    static final byte EMPTY = 0x00;
    static final byte DELETED = 0x01;
    static final long HIGH_BITS = 0x8080808080808080L;
    static final long LOW_BITS = 0x0101010101010101L;
    static final long GOLDEN = 0x9E3779B97F4A7C15L;
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * Offset of SwissTableBuffer.aux. Written only through Unsafe (setAux), like the slots: then every
     * reference store into a table marks the card of the stored-to reference, and the collectors
     * scan a table's dirty cards only, like an object array's (oopDesc::is_marked_precisely in the
     * VM); a putfield would mark the card of the object start, which need not hold aux.
     */
    private static final long AUX_OFFSET = U.objectFieldOffset(SwissTableBuffer.class, "aux");

    private static void setAux(SwissTableBuffer t, Object aux) { U.putReference(t, AUX_OFFSET, aux); }

    /** The table of every empty map with the default load factor; never written. */
    private static final SwissTableBuffer EMPTY_TABLE = SwissTableBuffer.allocate(0);

    /*
     * Instance state. The VM lays this class out without the fields of HashMap and AbstractMap
     * (see ClassFileParser), so an instance is an object header and these three fields, 24 bytes
     * with compressed oops; no code that runs on it may name an inherited field.
     *   buf        the table; EMPTY_TABLE or a capacity-0 table while nothing is allocated
     *   size       number of full slots
     *   modCount   structural modifications, for fail-fast iteration
     * The load factor requested at construction only matters for the serial form; a non-default
     * one rides in the table's aux field as a Float.
     */
    transient SwissTableBuffer buf;
    transient int size;
    transient int modCount;


    // ---- construction ------------------------------------------------------------------------
    // One constructor per HashMap constructor, with the same parameters, checks and exceptions:
    // `new HashMap(...)` outside HashMap and its subclasses runs these (the VM checks at startup
    // that each HashMap constructor has its counterpart). super() runs AbstractMap() (the VM binds
    // it so): no HashMap constructor runs on a SwissTable.

    SwissTable() {
        super();
        buf = EMPTY_TABLE;
    }

    SwissTable(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    SwissTable(int initialCapacity, float loadFactor) {
        super();
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal initial capacity: " +
                                               initialCapacity);
        if (initialCapacity > MAXIMUM_CAPACITY)
            initialCapacity = MAXIMUM_CAPACITY;
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("Illegal load factor: " +
                                               loadFactor);
        buf = initialCapacity > 0 ? presizedTable(initialCapacity, loadFactor) : emptyTable(loadFactor);
    }

    SwissTable(Map<? extends K, ? extends V> m) {
        super();
        buf = EMPTY_TABLE;
        putAllEntries(m);
    }

    /** {@link HashMap#newHashMap}, which the VM binds calls from outside HashMap and its subclasses to. */
    public static <K, V> HashMap<K, V> newHashMap(int numMappings) {
        if (numMappings < 0) {
            throw new IllegalArgumentException("Negative number of mappings: " + numMappings);
        }
        return new SwissTable<>(calculateHashMapCapacity(numMappings));
    }

    /** HashMap(n, lf) holds n * lf mappings before its first resize; hold as many. */
    private static SwissTableBuffer presizedTable(int initialCapacity, float loadFactor) {
        double entries = (double) initialCapacity * Math.min(loadFactor, 1.0f);
        Object aux = loadFactor == DEFAULT_LOAD_FACTOR ? null : (Object) loadFactor;
        return newTable(capacityFor((int) Math.ceil(Math.min(entries, MAX_CAPACITY))), aux);
    }

    /** An unallocated table that carries the load factor. */
    private static SwissTableBuffer emptyTable(float loadFactor) {
        if (loadFactor == DEFAULT_LOAD_FACTOR) return EMPTY_TABLE;
        SwissTableBuffer t = SwissTableBuffer.allocate(0);
        setAux(t, loadFactor);
        return t;
    }

    /** A zeroed table of the given capacity with all of its growth left. */
    private static SwissTableBuffer newTable(int capacity, Object aux) {
        SwissTableBuffer t = SwissTableBuffer.allocate(capacity);
        t.growthLeft = growthFor(capacity);
        if (aux instanceof Float) setAux(t, aux);         // a new table's aux is null: no store, no barrier
        return t;
    }

    /** Smallest power-of-two capacity whose 7/8 growth limit holds {@code n} entries. */
    static int capacityFor(int n) {
        long need = ((long) Math.max(n, 1) * 8 + 6) / 7;
        if (need >= MAX_CAPACITY) return MAX_CAPACITY;
        return need <= MIN_CAPACITY ? MIN_CAPACITY : Integer.highestOneBit((int) need - 1) << 1;
    }

    static int growthFor(int capacity) { return capacity - (capacity >>> 3); }

    /** Slots of t that are FULL or DELETED; at most growthFor(capacity) unless writers raced. */
    static int slotsInUse(SwissTableBuffer t) {
        int used = 0;
        for (int g = 0, groups = t.capacity >>> 3; g < groups; g++) used += 8 - Long.bitCount(matchEmpty(ctrlWord(t, g)));
        return used;
    }

    // ---- table access --------------------------------------------------------------------------

    /** Control word of group g: byte j describes slot 8g + j. */
    private static long ctrlWord(SwissTableBuffer t, int g) {
        long w = U.getLong(t, CTRL_BASE + ((long) g << 3));
        return BIG_ENDIAN ? Long.reverseBytes(w) : w;
    }

    private static byte ctrlByte(SwissTableBuffer t, int i) { return U.getByte(t, CTRL_BASE + i); }

    /** Store the control word of group g, in the lane order of {@link #ctrlWord}. */
    private static void putCtrlWord(SwissTableBuffer t, int g, long w) {
        U.putLong(t, CTRL_BASE + ((long) g << 3), BIG_ENDIAN ? Long.reverseBytes(w) : w);
    }

    /**
     * Control word w with lane j set to c. Control bytes are written through whole words: a later
     * 8-byte load of the word then forwards from the 8-byte store, where after a 1-byte store it
     * would wait for the store to complete (consecutive inserts into one group hit this).
     */
    private static long withLane(long w, int j, byte c) {
        int shift = j << 3;
        return (w & ~(0xFFL << shift)) | ((c & 0xFFL) << shift);
    }

    /** Control byte of lane j of control word w. */
    private static byte laneByte(long w, int j) { return (byte) (w >>> (j << 3)); }

    /** Byte offset of the reference region of a table of capacity cap. */
    private static long refs(int cap) { return CTRL_BASE + cap; }

    /*
     * The probes return the byte offset of a slot's key, its "key offset", rather than the slot
     * number: the value is at the key offset + REF_SIZE. Offsets fit an int: the VM keeps every
     * table below 2^31 bytes.
     */

    /** Key offset of slot i of a table of capacity cap. */
    static int keyOff(int cap, int i) { return CTRL_BASE + cap + (i << SLOT_SHIFT); }

    /** The slot whose key offset is off, in a table of capacity cap. */
    static int slotOf(int cap, int off) { return (off - CTRL_BASE - cap) >>> SLOT_SHIFT; }

    /**
     * Key offset of the lowest lane set in m, a nonzero lane mask of group g (high bit of each
     * lane), in a table of capacity cap: lane j has bit 8j + 7, so numberOfTrailingZeros(m) - 7 is
     * 8j, and the slot's key is j << SLOT_SHIFT bytes after the group's first key. Same as
     * keyOff(cap, (g << 3) + lane(m)), with fewer dependent operations after m.
     */
    static int laneOff(int cap, int g, long m) {
        return laneOffAt(cap, g, Long.numberOfTrailingZeros(m) - 7);
    }

    /** Key offset of lane j of group g in a table of capacity cap, given r = 8j (see firstTagLaneAt). */
    static int laneOffAt(int cap, int g, int r) {
        return CTRL_BASE + cap + (g << (SLOT_SHIFT + 3)) + (r << (SLOT_SHIFT - 3));
    }

    /** The reference at byte offset off of t. */
    @SuppressWarnings("unchecked")
    private static <T> T refAt(SwissTableBuffer t, int off) { return (T) U.getReference(t, off); }

    private static void setRef(SwissTableBuffer t, int off, Object v) { U.putReference(t, off, v); }

    private static long keyOffset(long refs, int i) { return refs + ((long) i << SLOT_SHIFT); }

    private static long valueOffset(long refs, int i) { return refs + ((long) i << SLOT_SHIFT) + (1L << REF_SHIFT); }

    @SuppressWarnings("unchecked")
    private static <T> T keyAt(SwissTableBuffer t, long refs, int i) { return (T) U.getReference(t, keyOffset(refs, i)); }

    @SuppressWarnings("unchecked")
    private static <T> T valueAt(SwissTableBuffer t, long refs, int i) { return (T) U.getReference(t, valueOffset(refs, i)); }

    private static void setValue(SwissTableBuffer t, long refs, int i, Object v) { U.putReference(t, valueOffset(refs, i), v); }


    // ---- hashing and group matching ----------------------------------------------------------

    /** Fibonacci hashing: the high half of the product depends on every bit of the hash code. */
    static long spread(Object key) {
        return (key == null) ? 0 : key.hashCode() * GOLDEN;
    }
    static int h1(long p) { return (int) (p >>> 32); }
    /** The control byte of a full slot with hash p. */
    static byte tag(long p) { return (byte) (0x80 | (int) (p >>> 57)); }
    /** The control byte of a full slot with hash p, in all 8 lanes. */
    static long tagBits(long p) { return ((p >>> 57) | 0x80L) * LOW_BITS; }

    /**
     * Candidate lanes for the tag broadcast in tagBits: every lane holding the tag, and possibly a
     * lane holding {@code tag ^ 1} directly above a candidate (the borrow of the subtraction
     * crosses into it). Such a lane is FULL, as the tag has its high bit set, and the lowest
     * candidate always holds the tag; the probes compare keys, so a false candidate costs one key
     * comparison.
     */
    static long matchTag(long w, long tagBits) {
        long x = w ^ tagBits;
        return (x - LOW_BITS) & ~x & HIGH_BITS;
    }
    /**
     * {@link #matchTag} for the control byte {@code tag} (0x80 | h2) on the control group at byte
     * offset ctrlOff of t. C2 intrinsifies it where the platform has a group compare (aarch64:
     * NEON, the instruction sequence of Abseil's and hashbrown's ARM groups), which then reports
     * exactly the lanes holding the tag; candidates are compared by key either way.
     */
    @IntrinsicCandidate
    static long matchTagAt(SwissTableBuffer t, long ctrlOff, int tag) {
        long w = U.getLong(t, ctrlOff);
        return matchTag(BIG_ENDIAN ? Long.reverseBytes(w) : w, (tag & 0xFFL) * LOW_BITS);
    }

    /**
     * 8j for the lowest lane j of the control group at byte offset ctrlOff of t that holds the
     * control byte {@code tag}, or a value of at least {@link #NO_LANE} when no lane does: the first
     * probe candidate, without the candidate set. The lowest candidate of matchTag always holds
     * the tag, so both forms are exact. C2 intrinsifies it where matchTagAt is intrinsified, on
     * the raw compare result (aarch64: cmeq, fmov, rbit, clz; 0xFF in every byte of an equal lane,
     * so its trailing zero count is 8j, or 64): matchTagAt has to keep only bit 7 of each lane to
     * iterate candidates, which the first candidate does not need. Abseil's NEON BitMask likewise
     * clears the low lane bits only when it advances past a candidate.
     */
    @IntrinsicCandidate
    static int firstTagLaneAt(SwissTableBuffer t, long ctrlOff, int tag) {
        return Long.numberOfTrailingZeros(matchTagAt(t, ctrlOff, tag)) - 7;
    }
    /** firstTagLaneAt results from NO_LANE (57: the Java form; 64: the intrinsic) mean no lane. */
    static final int NO_LANE = 57;

    /** The control byte of a full slot with hash p, as an int (0x80 | h2). */
    static int tagByte(long p) { return (int) (p >>> 57) | 0x80; }

    /**
     * EMPTY lanes, exactly: high bit and low bit clear. Bit 0 of a lane moves to bit 7 of the same
     * lane; a control byte with a clear high bit is EMPTY (0) or DELETED (1).
     */
    static long matchEmpty(long w) { return ~(w | (w << 7)) & HIGH_BITS; }
    /** EMPTY or DELETED lanes: high bit clear. */
    static long matchFree(long w) { return ~w & HIGH_BITS; }
    /** FULL lanes: high bit set. */
    static long matchFull(long w) { return w & HIGH_BITS; }
    static int lane(long bits) { return Long.numberOfTrailingZeros(bits) >>> 3; }

    // ---- probing -----------------------------------------------------------------------------

    /*
     * find() and findOrFree() settle the common cases from the first group alone: its first tag
     * candidate holds the key, or it has no candidate and an EMPTY slot. Anything else goes on in
     * findFull() or findOrFreeFull() over the whole probe sequence. The code inlined into the
     * callers thus keeps few values live: a deoptimization point inside an inlined probe loop
     * would need every loop variable, which C2 then spills on the hot path.
     */

    /** Key offset of {@code key} in t, or -1. */
    static int find(SwissTableBuffer t, Object key, long p) {
        int cap = t.capacity;
        int gmask = (cap >>> 3) - 1;
        if (gmask < 0) return -1;
        int g = h1(p) & gmask;
        int r = firstTagLaneAt(t, CTRL_BASE + ((long) g << 3), tagByte(p));
        if (r < NO_LANE) {
            int off = laneOffAt(cap, g, r);
            Object k = U.getReference(t, off);
            if (k == key || (key != null && key.equals(k))) return off;
        } else if (matchEmpty(ctrlWord(t, g)) != 0) {
            return -1;
        }
        return findFull(t, key, p);
    }

    /** {@link #find} over the whole probe sequence. */
    static int findFull(SwissTableBuffer t, Object key, long p) {
        int cap = t.capacity;
        int gmask = (cap >>> 3) - 1;
        if (gmask < 0) return -1;
        int tag = tagByte(p);
        int g = h1(p) & gmask;
        for (int step = 0; ; ) {
            long w = ctrlWord(t, g);
            for (long m = matchTagAt(t, CTRL_BASE + ((long) g << 3), tag); m != 0; m &= m - 1) {
                int off = laneOff(cap, g, m);
                Object k = U.getReference(t, off);
                if (k == key || (key != null && key.equals(k))) return off;
            }
            if (matchEmpty(w) != 0 || step == gmask) return -1;   // EMPTY seen, or every group visited
            g = (g + ++step) & gmask;
        }
    }

    /**
     * One probe for put-like operations. Returns the key offset of {@code key} if present (>= 0).
     * Otherwise the key is absent and the result is {@code ~off}, where {@code off} is the key
     * offset of the first EMPTY or DELETED slot on the probe sequence; or {@code NO_SLOT} if t has
     * no slots, or none is free (only at the largest capacity, or after racing writers).
     */
    static int findOrFree(SwissTableBuffer t, Object key, long p) {
        int cap = t.capacity;
        int gmask = (cap >>> 3) - 1;
        if (gmask < 0) return NO_SLOT;
        int g = h1(p) & gmask;
        int r = firstTagLaneAt(t, CTRL_BASE + ((long) g << 3), tagByte(p));
        if (r < NO_LANE) {
            int off = laneOffAt(cap, g, r);
            Object k = U.getReference(t, off);
            if (k == key || (key != null && key.equals(k))) return off;
        } else {
            long w = ctrlWord(t, g);
            if (matchEmpty(w) != 0) return ~laneOff(cap, g, matchFree(w));   // the first group is the first free
        }
        return findOrFreeFull(t, key, p);
    }

    /** {@link #findOrFree} over the whole probe sequence. */
    static int findOrFreeFull(SwissTableBuffer t, Object key, long p) {
        int cap = t.capacity;
        int gmask = (cap >>> 3) - 1;
        if (gmask < 0) return NO_SLOT;
        int tag = tagByte(p);
        int g = h1(p) & gmask;
        int free = -1;          // first free slot, -1 while none seen
        for (int step = 0; ; ) {
            long w = ctrlWord(t, g);
            for (long m = matchTagAt(t, CTRL_BASE + ((long) g << 3), tag); m != 0; m &= m - 1) {
                int off = laneOff(cap, g, m);
                Object k = U.getReference(t, off);
                if (k == key || (key != null && key.equals(k))) return off;
            }
            if (free < 0) {
                long f = matchFree(w);
                if (f != 0) free = (g << 3) + lane(f);
            }
            if (matchEmpty(w) != 0 || step == gmask) return free >= 0 ? ~keyOff(cap, free) : NO_SLOT;
            g = (g + ++step) & gmask;
        }
    }

    /** {@link #findOrFree} result: absent, and no free slot (no slots, or none free). */
    static final int NO_SLOT = Integer.MIN_VALUE;

    /** First full slot of t in {@code [from, n)}, or {@code n}; {@code n} is at most the capacity. */
    static int nextFull(SwissTableBuffer t, int from, int n) {
        if (from >= n) return n;
        int g = from >>> 3;
        long full = matchFull(ctrlWord(t, g)) & (-1L << ((from & 7) << 3));
        int groups = (n + GROUP - 1) >>> 3;
        for (;;) {
            if (full != 0) return Math.min((g << 3) + lane(full), n);
            if (++g >= groups) return n;
            full = matchFull(ctrlWord(t, g));
        }
    }

    // ---- core operations ---------------------------------------------------------------------

    @Override
    public int size() {
        return size;
    }

    @Override public boolean isEmpty() { return size == 0; }

    @Override
    public V get(Object key) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        return off >= 0 ? refAt(t, off + REF_SIZE) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return find(buf, key, spread(key)) >= 0;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        return off >= 0 ? refAt(t, off + REF_SIZE) : defaultValue;
    }

    @Override
    public V put(K key, V value) {
        SwissTableBuffer t = buf;
        long p = spread(key);
        int r = findOrFree(t, key, p);
        if (r >= 0) {
            V old = refAt(t, r + REF_SIZE);
            setRef(t, r + REF_SIZE, value);
            return old;
        }
        insertAt(t, r, key, value, p);
        return null;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        SwissTableBuffer t = buf;
        long p = spread(key);
        int r = findOrFree(t, key, p);
        if (r >= 0) {
            V old = refAt(t, r + REF_SIZE);
            if (old == null) setRef(t, r + REF_SIZE, value);
            return old;
        }
        insertAt(t, r, key, value, p);
        return null;
    }

    /**
     * Write a new mapping into a free slot of t: key offset off, lane j of group g, whose control
     * word is w (the caller accounted for growth).
     */
    private void place(SwissTableBuffer t, int off, Object key, Object value, int g, long w, int j, long p) {
        setRef(t, off, key);
        setRef(t, off + REF_SIZE, value);
        putCtrlWord(t, g, withLane(w, j, tag(p)));
        size++;
        modCount++;
    }

    /**
     * Insert a key known to be absent from t, the current table, given {@code r} from
     * {@link #findOrFree}: take the prepared free slot unless it is EMPTY and no growth is left.
     */
    private void insertAt(SwissTableBuffer t, int r, Object key, Object value, long p) {
        if (r != NO_SLOT) {
            int off = ~r;
            int i = slotOf(t.capacity, off), g = i >>> 3, j = i & 7;
            long w = ctrlWord(t, g);
            boolean empty = laneByte(w, j) == EMPTY;
            if (!empty || t.growthLeft > 0) {
                if (empty) t.growthLeft--;                // reusing a DELETED slot consumes no growth
                place(t, off, key, value, g, w, j, p);
                return;
            }
        } else if (t.capacity == 0 && t == buf) {
            insertFirst(t, key, value, p);
            return;
        }
        insertNew(key, value, p);
    }

    /**
     * The first insert into the table without slots (the caller checked): a table of FIRST_CAPACITY slots, where the key takes lane 0 of its group (the new
     * table is all EMPTY). Small enough to inline into put(), so that a short-lived small map costs
     * two allocations.
     */
    private void insertFirst(SwissTableBuffer t, Object key, Object value, long p) {
        SwissTableBuffer n = newTable(FIRST_CAPACITY, t.aux);
        n.growthLeft--;
        int g = h1(p) & ((FIRST_CAPACITY >>> 3) - 1);
        place(n, keyOff(FIRST_CAPACITY, g << 3), key, value, g, 0L, 0, p);
        buf = n;
    }

    /** Insert a key known to be absent: make room, then take the first free slot of its probe. */
    @SuppressWarnings("unchecked")
    private void insertNew(Object key, Object value, long p) {
        SwissTableBuffer t = buf;
        if (t.growthLeft <= 0) {                          // < 0 at the largest capacity, or after racing writers lost updates
            t = growForInsert();
        }
        int gmask = (t.capacity >>> 3) - 1;
        int g = h1(p) & gmask;
        long w, f;
        for (int step = 0; (f = matchFree(w = ctrlWord(t, g))) == 0; ) {
            if (++step > gmask) {                         // every group visited, none has a free slot
                if (t.capacity < MAX_CAPACITY) {
                    // Inserts keep 1/8 of the slots free below the largest capacity, so only
                    // unsynchronized concurrent writers fill a table: rebuild larger, count again.
                    SwissTableBuffer n = rehash(t.capacity << 1);
                    size = growthFor(n.capacity) - n.growthLeft;
                    insertNew(key, value, p);
                    return;
                }
                throw new OutOfMemoryError("SwissTable is full at its largest capacity, " + MAX_CAPACITY + " slots");
            }
            g = (g + step) & gmask;
        }
        int j = lane(f);
        if (laneByte(w, j) == EMPTY) t.growthLeft--;
        place(t, keyOff(t.capacity, (g << 3) + j), key, value, g, w, j, p);
    }

    /**
     * Make room for one more insert: allocate, purge tombstones at the same capacity, or double.
     * Returns the table to insert into: at the largest capacity, the full one, whose remaining free
     * slots the inserts then take (past the 7/8 limit; insertNew fails when none is left).
     * Never inlined: the rehash and purge loops it inlines then compile here, apart from the values
     * insertNew keeps live (key, value, hash). Inlined into insertNew, the rehash loop ran out of
     * registers and spilled; on Kunpeng a pair of 4-byte spill slots written one by one and read
     * back as one ldp cannot be forwarded, and growing a map took 28% longer (Put500 16.1 -> 20.6
     * us). Compiled here: 16.3 us. One call per table growth.
     */
    @DontInline
    private SwissTableBuffer growForInsert() {
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        if (cap == 0) {
            return buf = newTable(FIRST_CAPACITY, t.aux);
        } else if ((long) size * 32 <= (long) cap * 25) {
            // Abseil's rule: at most 25/32 full, purge the tombstones in place rather than grow,
            // so that a steady size under removes and inserts never grows the table. The purge
            // leaves at least 3/32 of the slots as growth, which bounds its amortized cost.
            purgeInPlace(t);
            return t;
        } else if (cap < MAX_CAPACITY) {
            return rehash(cap << 1);
        } else {
            return t;
        }
    }

    /**
     * Purge the tombstones of t in place, like hashbrown's rehash_in_place and Abseil's
     * DropDeletesWithoutResize: first every DELETED slot becomes EMPTY and every FULL slot
     * DELETED, which here means "still to place"; then each slot still to place is placed at the
     * first EMPTY-or-still-to-place slot of its probe. If that slot is in the element's own group,
     * the element stays; if it is EMPTY, the element moves there; if it holds another element still
     * to place, the two swap and the element swapped out is placed next. Each swap places one
     * element for good, so the work is bounded.
     */
    private void purgeInPlace(SwissTableBuffer t) {
        int cap = t.capacity, gmask = (cap >>> 3) - 1;
        for (int g = 0; g <= gmask; g++) {
            putCtrlWord(t, g, (ctrlWord(t, g) & HIGH_BITS) >>> 7);    // FULL -> DELETED, else EMPTY
        }
        int placements = 0;     // each placement settles a slot for good: at most cap without races
        for (int i = 0; i < cap; i++) {
            int gi = i >>> 3, li = i & 7;
            if (laneByte(ctrlWord(t, gi), li) != DELETED) continue;
            int off = keyOff(cap, i);
            for (;;) {
                if (++placements > cap) throw new ConcurrentModificationException();
                Object k = refAt(t, off);
                long p = spread(k);
                int g = h1(p) & gmask;
                long w, f;
                for (int step = 0; (f = matchFree(w = ctrlWord(t, g))) == 0; ) {
                    if (step == gmask) throw new ConcurrentModificationException();
                    g = (g + ++step) & gmask;
                }
                if (g == gi) {                                    // its group is the first with room
                    putCtrlWord(t, gi, withLane(ctrlWord(t, gi), li, tag(p)));
                    break;
                }
                int j = lane(f), toff = keyOff(cap, (g << 3) + j);
                Object v = refAt(t, off + REF_SIZE);
                if (laneByte(w, j) == EMPTY) {                    // move into the free slot
                    setRef(t, toff, k);
                    setRef(t, toff + REF_SIZE, v);
                    putCtrlWord(t, g, withLane(w, j, tag(p)));
                    setRef(t, off, null);
                    setRef(t, off + REF_SIZE, null);
                    putCtrlWord(t, gi, withLane(ctrlWord(t, gi), li, EMPTY));
                    break;
                }
                Object k2 = refAt(t, toff), v2 = refAt(t, toff + REF_SIZE);   // swap; slot i stays to place
                setRef(t, toff, k);
                setRef(t, toff + REF_SIZE, v);
                putCtrlWord(t, g, withLane(w, j, tag(p)));
                setRef(t, off, k2);
                setRef(t, off + REF_SIZE, v2);
            }
        }
        t.growthLeft = growthFor(cap) - size;
    }

    /**
     * Rebuild into a fresh table of {@code newCap} slots: walk the old groups' full lanes and put
     * each pair into the first free slot of its probe in the new table, which has no DELETED slots.
     */
    private SwissTableBuffer rehash(int newCap) {
        return buf = rehashed(buf, newCap);
    }

    /**
     * The mappings of {@code old} in a fresh table of {@code newCap} slots. Like Abseil's and
     * hashbrown's resize: for each full slot, recompute the hash and take the first free slot of
     * its probe in the new table, which has no DELETED slots. Static, and the value is read before
     * the hash code is computed: the type check on that call is a deoptimization point inside the
     * loop, and the fewer values live there, the less C2 keeps on the stack.
     */
    private static SwissTableBuffer rehashed(SwissTableBuffer old, int newCap) {
        SwissTableBuffer t = newTable(newCap, old.aux);
        int oldCap = old.capacity, gmask = (newCap >>> 3) - 1, moved = 0;
        for (int og = 0; og < (oldCap >>> 3); og++) {
            for (long full = matchFull(ctrlWord(old, og)); full != 0; full &= full - 1) {
                int ok = laneOff(oldCap, og, full);
                Object v = U.getReference(old, ok + REF_SIZE);
                Object k = U.getReference(old, ok);
                long p = spread(k);
                int g = h1(p) & gmask;
                long w, f;
                for (int step = 0; (f = matchFree(w = ctrlWord(t, g))) == 0; ) {
                    if (step == gmask) throw new ConcurrentModificationException();   // more entries than counted
                    g = (g + ++step) & gmask;
                }
                int off = laneOff(newCap, g, f);
                U.putReference(t, off, k);
                U.putReference(t, off + REF_SIZE, v);
                // the lane is EMPTY (a new table has no DELETED): or in the tag; 8 * lane = ntz(f) - 7
                putCtrlWord(t, g, w | (((p >>> 57) | 0x80L) << (Long.numberOfTrailingZeros(f) - 7)));
                moved++;
            }
        }
        t.growthLeft = growthFor(newCap) - moved;
        if (t.growthLeft < 0) throw new ConcurrentModificationException();
        return t;
    }

    /** Ensure {@code n} mappings fit without a rehash. */
    private void reserve(int n) {
        int need = capacityFor(n);
        SwissTableBuffer t = buf;
        if (need > t.capacity) {
            if (t.capacity == 0) buf = newTable(need, t.aux); else rehash(need);
        }
    }


    @Override
    public V remove(Object key) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        if (off >= 0) {
            V old = refAt(t, off + REF_SIZE);
            eraseAt(t, off);
            return old;
        }
        return null;
    }

    @Override
    public boolean remove(Object key, Object value) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        if (off < 0) return false;
        Object v = refAt(t, off + REF_SIZE);
        if (v == value || (value != null && value.equals(v))) {
            eraseAt(t, off);
            return true;
        }
        return false;
    }

    /** Free the slot of t whose key offset is off. */
    private void eraseAt(SwissTableBuffer t, int off) { eraseSlot(t, slotOf(t.capacity, off)); }

    /**
     * Free slot i of t. If its group still has an EMPTY slot, no probe ever continued past the
     * group, so the slot becomes EMPTY and returns its growth; otherwise it becomes DELETED.
     */
    private void eraseSlot(SwissTableBuffer t, int i) {
        int g = i >>> 3;
        long w = ctrlWord(t, g);
        if (matchEmpty(w) != 0) {
            putCtrlWord(t, g, withLane(w, i & 7, EMPTY));
            t.growthLeft++;
        } else {
            putCtrlWord(t, g, withLane(w, i & 7, DELETED));
        }
        long refs = refs(t.capacity);
        U.putReference(t, keyOffset(refs, i), null);
        U.putReference(t, valueOffset(refs, i), null);
        size--;
        modCount++;
    }

    @Override
    public void clear() {
        modCount++;
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        if (cap != 0 && (size != 0 || t.growthLeft != growthFor(cap))) {
            buf = newTable(cap, t.aux);                   // keeps the capacity, like HashMap.clear()
            size = 0;
        }
    }

    @Override
    public boolean containsValue(Object value) {
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                Object v = refAt(t, laneOff(cap, g, full) + REF_SIZE);
                if (v == value || (value != null && value.equals(v))) return true;
            }
        }
        return false;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        putAllEntries(m);
    }

    private void putAllEntries(Map<? extends K, ? extends V> m) {
        int s = m.size();
        if (s > 0) {
            if (m instanceof SwissTable<?, ?> st) {
                putAllFrom(st);
                return;
            }
            reserve((int) Math.min((long) size + s, MAX_CAPACITY));
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
                put(e.getKey(), e.getValue());
        }
    }

    /**
     * putAll (and the copy constructor) from another SwissTable: its slots are read directly, with
     * no entry objects; through entrySet() each mapping cost an entry allocation and the entry's
     * slot checks. Into an empty map that gets the source's capacity, the source table is copied
     * as is, with no hash computed; into another empty map the mappings are placed as a rehash
     * places them, with no lookup (the keys are distinct).
     */
    private void putAllFrom(SwissTable<?, ?> src) {
        SwissTableBuffer from = src.buf;
        int cap = from.capacity;
        if (cap == 0) return;
        SwissTableBuffer t = buf;
        if (size == 0) {
            int need = Math.max(t.capacity, capacityFor(src.size));
            if (need == cap) {
                buf = copyOf(from, t.aux);
                size = src.size;
            } else {
                SwissTableBuffer n = rehashed(from, need);
                setAux(n, t.aux);
                buf = n;
                size = growthFor(need) - n.growthLeft;
            }
            modCount++;
            return;
        }
        reserve((int) Math.min((long) size + src.size, MAX_CAPACITY));
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            for (long full = matchFull(ctrlWord(from, g)); full != 0; full &= full - 1) {
                int off = laneOff(cap, g, full);
                put(refAt(from, off), refAt(from, off + REF_SIZE));
            }
        }
    }

    // ---- functional methods ------------------------------------------------------------------

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        long p = spread(key);
        int r = findOrFree(t, key, p);
        if (r >= 0) {
            V old = refAt(t, r + REF_SIZE);
            if (old != null) return old;
        }
        int mc = modCount;
        V v = mappingFunction.apply(key);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (v == null) return null;
        if (r >= 0) setRef(t, r + REF_SIZE, v);
        else insertAt(t, r, key, v, p);                   // no modification since the probe: r is still valid
        return v;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        if (off < 0) return null;
        V old = refAt(t, off + REF_SIZE);
        if (old == null) return null;
        int mc = modCount;
        V v = remappingFunction.apply(key, old);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (v != null) {
            setRef(t, off + REF_SIZE, v);
            return v;
        }
        eraseAt(t, off);
        return null;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        long p = spread(key);
        int r = findOrFree(t, key, p);
        V old = r < 0 ? null : refAt(t, r + REF_SIZE);
        int mc = modCount;
        V v = remappingFunction.apply(key, old);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (r >= 0) {
            if (v != null) setRef(t, r + REF_SIZE, v);
            else eraseAt(t, r);
        } else if (v != null) {
            insertAt(t, r, key, v, p);
        }
        return v;
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null || remappingFunction == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        long p = spread(key);
        int r = findOrFree(t, key, p);
        if (r < 0) {
            insertAt(t, r, key, value, p);
            return value;
        }
        V old = refAt(t, r + REF_SIZE);
        V v;
        if (old != null) {
            int mc = modCount;
            v = remappingFunction.apply(old, value);
            if (mc != modCount) throw new ConcurrentModificationException();
        } else {
            v = value;
        }
        if (v != null) setRef(t, r + REF_SIZE, v);
        else eraseAt(t, r);
        return v;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        if (size > 0) {
            int mc = modCount;
            int cap = t.capacity;
            for (int g = 0, groups = cap >>> 3; g < groups; g++) {
                for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                    int off = laneOff(cap, g, full);
                    action.accept(refAt(t, off), refAt(t, off + REF_SIZE));
                }
            }
            if (modCount != mc) throw new ConcurrentModificationException();
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        SwissTableBuffer t = buf;
        if (size > 0) {
            int mc = modCount;
            int cap = t.capacity;
            for (int g = 0, groups = cap >>> 3; g < groups; g++) {
                for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                    int off = laneOff(cap, g, full);
                    setRef(t, off + REF_SIZE, function.apply(refAt(t, off), refAt(t, off + REF_SIZE)));
                }
            }
            if (modCount != mc) throw new ConcurrentModificationException();
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        if (off < 0) return false;
        Object v = refAt(t, off + REF_SIZE);
        if (v == oldValue || (v != null && v.equals(oldValue))) {
            setRef(t, off + REF_SIZE, newValue);
            return true;
        }
        return false;
    }

    @Override
    public V replace(K key, V value) {
        SwissTableBuffer t = buf;
        int off = find(t, key, spread(key));
        if (off < 0) return null;
        V old = refAt(t, off + REF_SIZE);
        setRef(t, off + REF_SIZE, value);
        return old;
    }

    // ---- HashMap internals used by HashSet, the views and serialization ----------------------

    /** Bucket count reported in the serial form (HashMap and HashSet): the slot capacity. */
    @Override
    int capacity() {
        SwissTableBuffer t = buf;
        return t.capacity == 0 ? 16 : t.capacity;
    }

    /** The load factor requested at construction (serial form of HashMap and HashSet). */
    @Override
    float loadFactor() {
        return buf.aux instanceof Float f ? f : DEFAULT_LOAD_FACTOR;
    }

    @Override
    @SuppressWarnings("unchecked")
    <T> T[] prepareArray(T[] a) {
        int size = size();
        if (a.length < size)
            return (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        if (a.length > size)
            a[size] = null;
        return a;
    }

    @Override
    <T> T[] keysToArray(T[] a) {
        return slotsToArray(a, 0);
    }

    @Override
    <T> T[] valuesToArray(T[] a) {
        return slotsToArray(a, 1);
    }

    private <T> T[] slotsToArray(T[] a, int which) {
        Object[] r = a;
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        int ref = which << REF_SHIFT;                     // 0: key, REF_SIZE: value
        int idx = 0;
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                r[idx++] = U.getReference(t, laneOff(cap, g, full) + ref);
            }
        }
        return a;
    }

    /** Reset to an empty map (clone and deserialization), keeping the load factor. */
    @Override
    void reinitialize() {
        buf = emptyTable(buf == null ? DEFAULT_LOAD_FACTOR : loadFactor());
        size = 0;
        modCount = 0;
    }

    // ---- views ---------------------------------------------------------------------------------

    @Override public Set<K> keySet() { return new KeySet(); }

    @Override public Collection<V> values() { return new Values(); }

    @Override public Set<Map.Entry<K,V>> entrySet() { return new EntrySet(); }

    private static final int KEYS = 0, VALUES = 1, ENTRIES = 2;

    /** Element {@code which} of slot i of t (a new entry for ENTRIES). */
    @SuppressWarnings("unchecked")
    <T> T element(SwissTableBuffer t, int which, int i) {
        if (which == ENTRIES) return (T) new SlotEntry(t, i);
        return (T) U.getReference(t, refs(t.capacity) + ((long) i << SLOT_SHIFT) + ((long) which << REF_SHIFT));
    }

    final class KeySet extends AbstractSet<K> {
        public int size() { return SwissTable.this.size(); }
        public void clear() { SwissTable.this.clear(); }
        public Iterator<K> iterator() { return new KeyIterator(); }
        public boolean contains(Object o) { return containsKey(o); }
        public boolean remove(Object key) {
            SwissTableBuffer t = buf;
            int off = find(t, key, spread(key));
            if (off < 0) return false;
            eraseAt(t, off);
            return true;
        }
        public Object[] toArray() { return keysToArray(new Object[SwissTable.this.size()]); }
        public <T> T[] toArray(T[] a) { return keysToArray(prepareArray(a)); }
        public Spliterator<K> spliterator() {
            return new SlotSpliterator<>(buf, KEYS, 0, -1, 0, 0);
        }
        public void forEach(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            SwissTableBuffer t = buf;
            if (size > 0) {
                int mc = modCount;
                int cap = t.capacity;
                for (int g = 0, groups = cap >>> 3; g < groups; g++) {
                    for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                        action.accept(refAt(t, laneOff(cap, g, full)));
                    }
                }
                if (modCount != mc) throw new ConcurrentModificationException();
            }
        }
    }

    final class Values extends AbstractCollection<V> {
        public int size() { return SwissTable.this.size(); }
        public void clear() { SwissTable.this.clear(); }
        public Iterator<V> iterator() { return new ValueIterator(); }
        public boolean contains(Object o) { return containsValue(o); }
        public Object[] toArray() { return valuesToArray(new Object[SwissTable.this.size()]); }
        public <T> T[] toArray(T[] a) { return valuesToArray(prepareArray(a)); }
        public Spliterator<V> spliterator() {
            return new SlotSpliterator<>(buf, VALUES, 0, -1, 0, 0);
        }
        public void forEach(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            SwissTableBuffer t = buf;
            if (size > 0) {
                int mc = modCount;
                int cap = t.capacity;
                for (int g = 0, groups = cap >>> 3; g < groups; g++) {
                    for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                        action.accept(refAt(t, laneOff(cap, g, full) + REF_SIZE));
                    }
                }
                if (modCount != mc) throw new ConcurrentModificationException();
            }
        }
    }

    final class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public int size() { return SwissTable.this.size(); }
        public void clear() { SwissTable.this.clear(); }
        public Iterator<Map.Entry<K,V>> iterator() { return new EntryIterator(); }
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            SwissTableBuffer t = buf;
            Object key = e.getKey();
            int off = find(t, key, spread(key));
            if (off < 0) return false;
            Object v = refAt(t, off + REF_SIZE), ev = e.getValue();
            return v == ev || (v != null && v.equals(ev));
        }
        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return SwissTable.this.remove(e.getKey(), e.getValue());
        }
        public Spliterator<Map.Entry<K,V>> spliterator() {
            return new SlotSpliterator<>(buf, ENTRIES, 0, -1, 0, 0);
        }
        public void forEach(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null) throw new NullPointerException();
            SwissTableBuffer t = buf;
            if (size > 0) {
                int mc = modCount;
                for (int g = 0, groups = t.capacity >>> 3; g < groups; g++) {
                    for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                        action.accept(new SlotEntry(t, (g << 3) + lane(full)));
                    }
                }
                if (modCount != mc) throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * An entry bound to a slot of a table. Reads and {@code setValue} go through the slot while the
     * map still uses that table and the slot still holds the key; after a rehash moved the key they
     * go through a lookup. {@code setValue} never inserts: once the key has been removed it changes
     * nothing and returns null (a removed HashMap node is detached from the map in the same way).
     */
    final class SlotEntry implements Map.Entry<K,V> {
        private final SwissTableBuffer table;
        private final int slot;
        private final K key;

        SlotEntry(SwissTableBuffer table, int slot) {
            this.table = table;
            this.slot = slot;
            this.key = keyAt(table, refs(table.capacity), slot);
        }

        private boolean bound() {
            SwissTableBuffer t = table;
            if (t != buf || ctrlByte(t, slot) >= 0) return false;      // full control bytes are negative
            Object k = keyAt(t, refs(t.capacity), slot);
            return k == key || (key != null && key.equals(k));
        }

        public K getKey() { return key; }

        public V getValue() { return bound() ? valueAt(table, refs(table.capacity), slot) : get(key); }

        public V setValue(V value) {
            if (bound()) {
                long refs = refs(table.capacity);
                V old = valueAt(table, refs, slot);
                SwissTable.setValue(table, refs, slot, value);
                return old;
            }
            return replace(key, value);
        }

        public int hashCode() { return Objects.hashCode(key) ^ Objects.hashCode(getValue()); }

        public boolean equals(Object o) {
            return o == this || (o instanceof Map.Entry<?, ?> e
                    && Objects.equals(key, e.getKey())
                    && Objects.equals(getValue(), e.getValue()));
        }

        public String toString() { return key + "=" + getValue(); }
    }

    /**
     * Walks the full slots of the table current at creation, group by group.
     * Like hashbrown's RawIter, it counts the slots still to return: hasNext() is a counter test,
     * and the walk stops at the last full slot instead of scanning the empty groups after it.
     */
    abstract class SlotIterator<E> implements Iterator<E> {
        final SwissTableBuffer table = buf;
        final int cap = table.capacity;
        private int g = -1;                             // group of bits
        private long bits;                              // full lanes of group g not returned yet
        private int remaining = size;                   // full slots not returned yet
        private int current = -1;                       // key offset of the last slot returned
        private int expectedModCount = modCount;

        public final boolean hasNext() { return remaining != 0; }

        /** Key offset of the next full slot (its value is at + REF_SIZE). */
        final int nextOff() {
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            if (remaining == 0) throw new NoSuchElementException();
            long b = bits;
            while (b == 0) {                              // the next group with a full slot
                if (++g >= (cap >>> 3)) throw new ConcurrentModificationException();   // fewer than counted
                b = matchFull(ctrlWord(table, g));
            }
            int off = laneOff(cap, g, b);
            bits = b & (b - 1);
            remaining--;
            current = off;
            return off;
        }

        public final void remove() {
            int off = current;
            if (off < 0) throw new IllegalStateException();
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            current = -1;
            eraseSlot(table, slotOf(cap, off));
            expectedModCount = modCount;
        }
    }


    final class KeyIterator extends SlotIterator<K> {
        public K next() {
            return refAt(table, nextOff());
        }
    }

    final class ValueIterator extends SlotIterator<V> {
        public V next() {
            return refAt(table, nextOff() + REF_SIZE);
        }
    }

    final class EntryIterator extends SlotIterator<Map.Entry<K,V>> {
        public Map.Entry<K,V> next() {
            return new SlotEntry(table, slotOf(cap, nextOff()));
        }
    }

    /**
     * Splits the slot range of one table like HashMap's spliterators split the bucket range, and
     * reports the same characteristics: SIZED only before splitting, DISTINCT except for values.
     */
    final class SlotSpliterator<T> implements Spliterator<T> {
        private final SwissTableBuffer table;
        private final int which;
        private int index;            // next slot to visit
        private int fence;            // one past the last slot; -1 until first use
        private int est;              // size estimate
        private int expectedModCount; // set with fence

        SlotSpliterator(SwissTableBuffer table, int which, int origin, int fence, int est, int expectedModCount) {
            this.table = table;
            this.which = which;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        private int getFence() {
            int hi;
            if ((hi = fence) < 0) {
                est = size;
                expectedModCount = modCount;
                hi = fence = table.capacity;
            }
            return hi;
        }

        public SlotSpliterator<T> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid) ? null
                    : new SlotSpliterator<>(table, which, lo, index = mid, est >>>= 1, expectedModCount);
        }

        public boolean tryAdvance(Consumer<? super T> action) {
            if (action == null) throw new NullPointerException();
            int hi = getFence();
            int i = nextFull(table, index, hi);
            if (i < hi) {
                index = i + 1;
                action.accept(element(table, which, i));
                if (modCount != expectedModCount) throw new ConcurrentModificationException();
                return true;
            }
            index = hi;
            return false;
        }

        /**
         * The full slots of [index, fence) a control word at a time, like forEach: per element only
         * the lane arithmetic, where tryAdvance (nextFull, element) reloads the word and the capacity.
         */
        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super T> action) {
            if (action == null) throw new NullPointerException();
            int hi = getFence(), mc = expectedModCount;
            int i = index;
            index = hi;
            if (i < hi) {
                SwissTableBuffer t = table;
                int cap = t.capacity, ref = which == VALUES ? REF_SIZE : 0;
                int g = i >>> 3, last = (hi - 1) >>> 3;
                long full = matchFull(ctrlWord(t, g)) & (-1L << ((i & 7) << 3));     // lanes from i
                for (;;) {
                    if (g == last && (hi & 7) != 0) full &= (1L << ((hi & 7) << 3)) - 1;  // lanes below hi
                    for (; full != 0; full &= full - 1) {
                        int off = laneOff(cap, g, full);
                        action.accept(which == ENTRIES ? (T) new SlotEntry(t, slotOf(cap, off)) : (T) U.getReference(t, off + ref));
                    }
                    if (++g > last) break;
                    full = matchFull(ctrlWord(t, g));
                }
            }
            if (modCount != mc) throw new ConcurrentModificationException();
        }

        public long estimateSize() {
            getFence();
            return est;
        }

        public int characteristics() {
            int c = (fence < 0 || est == size) ? Spliterator.SIZED : 0;
            return which == VALUES ? c : c | Spliterator.DISTINCT;
        }
    }

    // ---- cloning, equality and serialization ---------------------------------------------------

    /**
     * A shallow copy with its own table. Does not call HashMap.clone(), whose superclass part
     * (AbstractMap.clone) writes AbstractMap's view fields, which this layout does not have.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object clone() {
        SwissTable<K,V> result = new SwissTable<>();
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        if (cap == 0) {
            result.buf = t;                               // unallocated tables are never written
            return result;
        }
        result.buf = copyOf(t, t.aux);
        result.size = size;
        return result;
    }

    /**
     * A copy of table t (capacity > 0) with the given aux: the same control words, so the same
     * slots, tombstones and growth; the references of the full slots (the others are null).
     */
    private static SwissTableBuffer copyOf(SwissTableBuffer t, Object aux) {
        int cap = t.capacity;
        SwissTableBuffer c = newTable(cap, aux);
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            long w = ctrlWord(t, g);
            putCtrlWord(c, g, w);
            for (long full = matchFull(w); full != 0; full &= full - 1) {
                int off = laneOff(cap, g, full);
                U.putReference(c, off, U.getReference(t, off));
                U.putReference(c, off + REF_SIZE, U.getReference(t, off + REF_SIZE));
            }
        }
        c.growthLeft = t.growthLeft;
        return c;
    }

    @Override
    public int hashCode() {
        SwissTableBuffer t = buf;
        int h = 0;
        int cap = t.capacity;
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                int off = laneOff(cap, g, full);
                h += Objects.hashCode(refAt(t, off)) ^ Objects.hashCode(refAt(t, off + REF_SIZE));
            }
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        SwissTableBuffer t = buf;
        if (!(o instanceof Map<?, ?> m) || m.size() != size) return false;
        int cap = t.capacity;
        try {
            for (int g = 0, groups = cap >>> 3; g < groups; g++) {
                for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                    int off = laneOff(cap, g, full);
                    Object k = refAt(t, off), v = refAt(t, off + REF_SIZE);
                    if (v == null) {
                        if (!(m.get(k) == null && m.containsKey(k))) return false;
                    } else if (!v.equals(m.get(k))) {
                        return false;
                    }
                }
            }
        } catch (ClassCastException | NullPointerException unused) {
            return false;
        }
        return true;
    }

    // ---- serialization -------------------------------------------------------------------------
    // HashMap's serial form, written and read here: java.io calls these methods for a SwissTable
    // in place of HashMap's (whose class descriptor the stream names), and allocates a SwissTable
    // for a HashMap in the stream. The serial fields go through putFields/readFields, by name: the
    // descriptor's field offsets are HashMap's, which this class does not have.

    /**
     * HashMap.writeObject: the serial fields threshold (what a node-based map with this bucket
     * count carries; readers ignore it) and loadFactor, the bucket count, the size, then the mappings.
     */
    @java.io.Serial
    private void writeObject(ObjectOutputStream s) throws IOException {
        int buckets = capacity();
        float lf = loadFactor();
        ObjectOutputStream.PutField fields = s.putFields();
        fields.put("threshold", (int) Math.min((float) buckets * lf, Integer.MAX_VALUE));
        fields.put("loadFactor", lf);
        s.writeFields();
        s.writeInt(buckets);
        s.writeInt(size());
        internalWriteEntries(s);
    }

    /**
     * HashMap.readObject: the same checks, and the same array-size check against the stream's
     * filter (with the bucket count HashMap would allocate), then a table for the mappings.
     */
    @java.io.Serial
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = s.readFields();

        // Read loadFactor (ignore threshold)
        float lf = fields.get("loadFactor", 0.75f);
        if (lf <= 0 || Float.isNaN(lf))
            throw new InvalidObjectException("Illegal load factor: " + lf);
        lf = Math.clamp(lf, 0.25f, 4.0f);

        buf = emptyTable(lf);
        size = 0;
        modCount = 0;

        s.readInt();                // Read and ignore number of buckets
        int mappings = s.readInt(); // Read number of mappings (size)
        if (mappings < 0) {
            throw new InvalidObjectException("Illegal mappings count: " + mappings);
        } else if (mappings > 0) {
            double dc = Math.ceil(mappings / (double)lf);
            int nodeCap = ((dc < DEFAULT_INITIAL_CAPACITY) ?
                           DEFAULT_INITIAL_CAPACITY :
                           (dc >= MAXIMUM_CAPACITY) ?
                           MAXIMUM_CAPACITY :
                           tableSizeFor((int)dc));
            SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Map.Entry[].class, nodeCap);
            buf = newTable(capacityFor(mappings), buf.aux);

            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                    K key = (K) s.readObject();
                @SuppressWarnings("unchecked")
                    V value = (V) s.readObject();
                put(key, value);
            }
        }
    }

    /** HashMap.writeObject: the mappings, in iteration order. */
    @Override
    void internalWriteEntries(ObjectOutputStream s) throws IOException {
        SwissTableBuffer t = buf;
        int cap = t.capacity;
        long refs = refs(cap);
        for (int g = 0, groups = cap >>> 3; g < groups; g++) {
            for (long full = matchFull(ctrlWord(t, g)); full != 0; full &= full - 1) {
                int i = (g << 3) + lane(full);
                s.writeObject(keyAt(t, refs, i));
                s.writeObject(valueAt(t, refs, i));
            }
        }
    }

}
