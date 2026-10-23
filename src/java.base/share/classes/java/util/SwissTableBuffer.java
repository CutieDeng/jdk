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

import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * The single storage buffer of a {@link SwissTable}: one heap object that holds the table's
 * control bytes and its key/value references. The VM defines the layout (InstanceSwissTableBufferKlass):
 * after the declared fields, at {@link #ctrlBase()}, come {@code capacity} control bytes, then
 * {@code 2 * capacity} references, the key and value of slot i at 2i and 2i + 1. Instances come
 * only from {@link #allocate}, zeroed: every control byte is EMPTY (0) and every reference null.
 * The garbage collectors visit {@link #aux} and the trailing references; Java code accesses
 * the trailing data with Unsafe, so every reference access carries the collector's barriers.
 */
final class SwissTableBuffer {
    /** Number of slots: 0, or a power of two of at least 8. Written by the VM at allocation. */
    final int capacity;
    /** Inserts into EMPTY slots possible before a rehash. */
    int growthLeft;
    /** Null, or the Float load factor a map was constructed with when it is not the default. */
    Object aux;

    private SwissTableBuffer() {
        throw new AssertionError("allocated by the VM");
    }

    /**
     * A zeroed table of the given capacity (0, or a power of two of at least 8); throws
     * IllegalArgumentException for any other capacity. C2 allocates it inline from the TLAB.
     */
    @IntrinsicCandidate
    static native SwissTableBuffer allocate(int capacity);

    /** Byte offset of the first control byte within a table. */
    static native int ctrlBase();

    /** The largest capacity the VM allocates. */
    static native int maxCapacity();

    private static native void registerNatives();

    static {
        registerNatives();
    }
}
