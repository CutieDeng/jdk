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

package compiler.c2.aarch64;

import compiler.lib.ir_framework.*;

/*
 * @test
 * @summary UsePrefetchStore puts PRFM PSTL1KEEP in front of the load of a
 *          load/store pair on one address, and nowhere else.
 * @requires os.arch == "aarch64" & vm.debug == true & vm.compiler2.enabled
 * @library /test/lib /
 * @run driver compiler.c2.aarch64.TestPrefetchStore
 */
public class TestPrefetchStore {
    static final String PREFETCH = "prfm[^\\n]*PSTL1KEEP\\t# Prefetch for store";
    // The prefetch of [Rn, #off] comes before the 32-bit load of [Rn, #off].
    static final String PREFETCH_THEN_LOAD =
        "prfm \\[(R\\d+), #(\\d+)\\], PSTL1KEEP\\t# Prefetch for store\\n" +
        "(?:[^\\n]*\\n)*?[^\\n]*ldrw\\s+R\\d+, \\[\\1, #\\2\\]";

    static class Box {
        int i;
        int j;
        long l;
        volatile int v;

        void inc() {
            i++;
        }
    }

    static final Box BOX = new Box();
    static final int[] A = new int[16];
    static final int[] B = new int[16];
    static int k;

    public static void main(String[] args) {
        new TestFramework()
            .addScenarios(new Scenario(0, "-XX:+UnlockExperimentalVMOptions", "-XX:+UsePrefetchStore"),
                          new Scenario(1, "-XX:+UnlockExperimentalVMOptions", "-XX:-UsePrefetchStore"))
            .start();
    }

    // o.i is loaded as the implicit null check of o; the store follows in
    // the not-null successor.
    @Test
    @IR(counts = {PREFETCH, "1", PREFETCH_THEN_LOAD, "1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    @IR(failOn = PREFETCH, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "false"})
    static void field(Box o) {
        o.i++;
    }

    @Run(test = "field")
    static void runField() {
        field(BOX);
    }

    @Test
    @IR(counts = {PREFETCH, "1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    static void method() {
        BOX.inc();
    }

    @Run(test = "method")
    static void runMethod() {
        method();
    }

    // The old value is also returned.
    @Test
    @IR(counts = {PREFETCH, "1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    static long nextId(Box o) {
        return o.l++;
    }

    @Run(test = "nextId")
    static void runNextId() {
        nextId(BOX);
    }

    @Test
    @IR(counts = {PREFETCH, "1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    static void volatileField(Box o) {
        o.v++;
    }

    @Run(test = "volatileField")
    static void runVolatileField() {
        volatileField(BOX);
    }

    @Test
    @IR(counts = {PREFETCH, "1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    static void arrayElement(int[] a, int i) {
        a[i] += i;
    }

    @Run(test = "arrayElement")
    static void runArrayElement() {
        arrayElement(A, k++ & 15);
    }

    // The range check of b[j] is between the load and the store of a[i], and
    // so is a store that may alias a[i].
    @Test
    @IR(counts = {PREFETCH, "1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"UsePrefetchStore", "true"})
    static void interleaved(int[] a, int[] b, int i, int j) {
        int t = a[i];
        b[j] = 7;
        a[i] = t + 1;
    }

    @Run(test = "interleaved")
    static void runInterleaved() {
        int i = k++ & 15;
        interleaved(A, B, i, (i + 1) & 15);
    }

    // Load and store on different fields.
    @Test
    @IR(failOn = PREFETCH, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    static void differentFields(Box o) {
        o.i = o.j + 1;
    }

    @Run(test = "differentFields")
    static void runDifferentFields() {
        differentFields(BOX);
    }

    // The store is on one of two common paths after the load.
    @Test
    @IR(failOn = PREFETCH, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    static void checkBeforeWrite(Box o, int v) {
        if (o.j != v) {
            o.j = v;
        }
    }

    @Run(test = "checkBeforeWrite")
    static void runCheckBeforeWrite() {
        checkBeforeWrite(BOX, BOX.j);
        checkBeforeWrite(BOX, BOX.j + 1);
    }
}
