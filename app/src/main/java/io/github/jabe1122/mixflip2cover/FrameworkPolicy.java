package io.github.jabe1122.mixflip2cover;

/**
 * Pure policy helpers used by the system_server hooks (no Android framework state).
 */
final class FrameworkPolicy {
    static final int STATUS_BAR_TYPE = 1;
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    private FrameworkPolicy() {}

    /**
     * The cover panel is identified by its physical size on a built-in ("local:") display. The size is unique on
     * MIX Flip 2 (inner panel is 1224x2912), and the unique id differs between individual devices, so it is not
     * used as a key.
     */
    static boolean coverDisplay(String uniqueId, int width, int height) {
        return uniqueId != null
                && uniqueId.startsWith("local:")
                && CoverDisplay.isOuterDimensions(width, height);
    }

    static boolean eligible(String uniqueId, int width, int height, int type, boolean activity, int windowingMode) {
        boolean application = (type >= 1 && type <= 99) || (type >= 1000 && type <= 1999);
        return coverDisplay(uniqueId, width, height)
                && application
                && activity
                && (windowingMode == 0
                        || windowingMode == WINDOWING_MODE_FULLSCREEN
                        || windowingMode == WINDOWING_MODE_MULTI_WINDOW);
    }

    static int filteredTypes(int original, int statusBarType) {
        return original & ~statusBarType;
    }

    static boolean nativeFullscreen(int requested, boolean fullscreenFlag, boolean home) {
        return !home && (fullscreenFlag || (requested & STATUS_BAR_TYPE) == 0);
    }
}
