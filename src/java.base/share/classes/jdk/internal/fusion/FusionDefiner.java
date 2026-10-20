/*
 * FilterFusion: internal entry point for defining access-check-bypassing hidden classes.
 * Not exported from java.base; the fusion agent opens this package to itself with
 * Instrumentation.redefineModule. Guarded by -XX:+FilterFusion in the VM.
 */
package jdk.internal.fusion;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

import jdk.internal.access.SharedSecrets;

public final class FusionDefiner {
    private FusionDefiner() {}

    /**
     * Defines {@code bytes} as a hidden nestmate class of {@code lookup}'s class.
     * The VM marks it {@code is_fusion_trusted}: member and class access checks from
     * the generated class succeed unconditionally.
     *
     * @throws IllegalAccessException if {@code lookup} lacks full privilege access
     * @throws UnsupportedOperationException if the VM was started without -XX:+FilterFusion
     */
    public static Class<?> defineTrusted(MethodHandles.Lookup lookup, byte[] bytes)
            throws IllegalAccessException {
        Objects.requireNonNull(lookup);
        Objects.requireNonNull(bytes);
        return SharedSecrets.getJavaLangInvokeAccess().defineFusionTrustedHiddenClass(lookup, bytes);
    }
}
