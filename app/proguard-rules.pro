# Native code resolves the bridge class, its native methods and its callbacks by name.
-keep class io.github.besliky.airplaytv.core.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Release builds must not ship verbose logging calls.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
