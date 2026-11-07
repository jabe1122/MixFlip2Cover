# Defensive R8 / ProGuard rules for MixFlip2Cover (libxposed API 101)
# Keep Xposed module entry points listed in META-INF/xposed/java_init.list and their no-arg constructors
-keep public class io.github.jabe1122.mixflip2cover.SystemUiEntry {
    public <init>();
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface$SystemServerStartingParam);
}

-keep public class io.github.jabe1122.mixflip2cover.SystemServerEntry {
    public <init>();
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface$SystemServerStartingParam);
}

# Keep libxposed API (compileOnly) and service/interface AIDL & ContentProvider classes
-dontwarn io.github.libxposed.api.**
-keep class io.github.libxposed.** { *; }

# Keep MainActivity and BuildConfig metadata
-keep public class io.github.jabe1122.mixflip2cover.MainActivity { *; }
-keep class io.github.jabe1122.mixflip2cover.BuildConfig { *; }
