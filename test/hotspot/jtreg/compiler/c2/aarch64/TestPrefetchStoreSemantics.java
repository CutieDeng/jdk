/*
 * Copyright (c) 2026, cutie-deng. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

/*
 * @test
 * @summary Read-modify-write code compiled with UsePrefetchStore gives the
 *          same results, and an implicit null check with a prefetch in front
 *          of its load still throws NullPointerException.
 * @requires os.arch == "aarch64" & vm.compiler2.enabled
 * @run main/othervm -XX:+UnlockExperimentalVMOptions -XX:+UsePrefetchStore
 *                   -Xbatch -XX:-TieredCompilation
 *                   compiler.c2.aarch64.TestPrefetchStoreSemantics
 * @run main/othervm -XX:+UnlockExperimentalVMOptions -XX:+UsePrefetchStore
 *                   -Xbatch -XX:-TieredCompilation -XX:-UseCompressedOops
 *                   compiler.c2.aarch64.TestPrefetchStoreSemantics
 */

package compiler.c2.aarch64;

public class TestPrefetchStoreSemantics {
    static final int ITERS = 50_000;

    static class Box {
        int i;
        long l;
        byte b;
        short s;
        char c;
        float f;
        double d;
        volatile int v;
    }

    static void fields(Box o) {
        o.i++;
        o.l += 3;
        o.b++;
        o.s--;
        o.c++;
        o.f += 0.5f;
        o.d += 0.25;
        o.v++;
    }

    static int nextId(Box o) {
        return o.i++;
    }

    static void arrays(int[] a, long[] la, byte[] ba, char[] ca, double[] da, int k) {
        a[k] += k;
        la[k] -= k;
        ba[k] ^= 1;
        ca[k]++;
        da[k] += 1.0;
    }

    // A store to another int[] between the load and the store of a[i].
    static void interleaved(int[] a, int[] b, int i, int j) {
        int t = a[i];
        b[j] = 7;
        a[i] = t + 1;
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new RuntimeException("wrong result: " + what);
        }
    }

    public static void main(String[] args) {
        Box o = new Box();
        for (int n = 0; n < ITERS; n++) {
            fields(o);
        }
        check(o.i == ITERS, "i");
        check(o.l == 3L * ITERS, "l");
        check(o.b == (byte) ITERS, "b");
        check(o.s == (short) -ITERS, "s");
        check(o.c == (char) ITERS, "c");
        check(o.f == 0.5f * ITERS, "f");
        check(o.d == 0.25 * ITERS, "d");
        check(o.v == ITERS, "v");

        Box ids = new Box();
        for (int n = 0; n < ITERS; n++) {
            check(nextId(ids) == n, "nextId");
        }

        int len = 16;
        int[] a = new int[len];
        long[] la = new long[len];
        byte[] ba = new byte[len];
        char[] ca = new char[len];
        double[] da = new double[len];
        for (int n = 0; n < ITERS; n++) {
            arrays(a, la, ba, ca, da, n % len);
        }
        int rounds = ITERS / len;
        for (int k = 0; k < len; k++) {
            check(a[k] == k * rounds, "a[" + k + "]");
            check(la[k] == -(long) k * rounds, "la[" + k + "]");
            check(ba[k] == (byte) (rounds & 1), "ba[" + k + "]");
            check(ca[k] == (char) rounds, "ca[" + k + "]");
            check(da[k] == rounds, "da[" + k + "]");
        }

        int[] x = new int[len];
        int[] y = new int[len];
        for (int n = 0; n < ITERS; n++) {
            interleaved(x, y, n % len, (n + 1) % len);
        }
        check(x[0] == rounds && y[0] == 7, "interleaved, distinct arrays");
        // Same array and index: the store of 7 is overwritten by t + 1.
        int[] z = new int[1];
        for (int n = 0; n < ITERS; n++) {
            interleaved(z, z, 0, 0);
        }
        check(z[0] == ITERS, "interleaved, same element");

        // fields() is compiled with a null check on o; null must still throw.
        for (int n = 0; n < 3; n++) {
            try {
                fields(null);
                throw new RuntimeException("no NullPointerException");
            } catch (NullPointerException e) {
                // expected
            }
        }
    }
}
