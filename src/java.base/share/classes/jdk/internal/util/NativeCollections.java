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

package jdk.internal.util;

/**
 * Field access guard for the native representation of {@code java.util.HashMap}
 * ({@code -XX:+UseSwissTable}). A native HashMap (java.util.SwissTable) is laid out
 * without the instance fields of HashMap and AbstractMap, but getClass() of it reports HashMap,
 * so a reflective accessor, method handle or VarHandle for such a field would otherwise read or
 * write memory of a different field, or past the object. Accessors for these fields call
 * {@link #checkReceiver} first; all other accessors are unaffected.
 */
public final class NativeCollections {
    private NativeCollections() {}

    private static final Class<?> NATIVE_HASH_MAP = nativeHashMapClass();

    private static Class<?> nativeHashMapClass() {
        try {
            return Class.forName("java.util.SwissTable", false, null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Whether accessors of this field need {@link #checkReceiver}. */
    public static boolean isGuardedField(Class<?> declaringClass, boolean isStatic) {
        return !isStatic
                && (declaringClass == java.util.HashMap.class || declaringClass == java.util.AbstractMap.class);
    }

    /** Returns {@code receiver}, or throws if it is a native HashMap, which has no such field. */
    public static Object checkReceiver(Object receiver) {
        if (NATIVE_HASH_MAP != null && NATIVE_HASH_MAP.isInstance(receiver)) {
            throw new IllegalArgumentException(
                    "field of java.util.HashMap or java.util.AbstractMap accessed on a native HashMap"
                    + " (-XX:+UseSwissTable), which does not have it");
        }
        return receiver;
    }
}
