package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Compile-time stub of the Xposed API; see XC_MethodHook for the rationale. */
public final class XposedBridge {
    private XposedBridge() {}

    /**
     * Returns [XC_MethodHook.Unhook] in the real API. Declaring it as
     * `Object` compiles but produces a call site the framework cannot
     * resolve: `NoSuchMethodError: No static method hookMethod(...)
     * Ljava/lang/Object;` — the descriptor is part of the lookup.
     */
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }
}
