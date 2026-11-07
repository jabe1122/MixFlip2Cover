package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Process;
import android.view.Display;
import android.view.Surface;

/**
 * Hardware facts for the Xiaomi MIX Flip 2 (codename bixi) cover display and the firmware gate.
 *
 * Geometry (all values in physical pixels, portrait orientation, measured on bixi HyperOS 4):
 * cover panel 1208x1392 @ 520 dpi, rear camera cut-out occupies the lower-right block
 * Rect(810, 664 - 1208, 1392), so the usable full-height column on the left is 810 px wide.
 */
final class CoverDisplay {
    static final int WIDTH = 1208;
    static final int HEIGHT = 1392;
    static final int PHYSICAL_WIDTH = WIDTH;
    static final int PHYSICAL_HEIGHT = HEIGHT;
    static final int DENSITY_DPI = 520;
    /** Left edge of the camera cut-out == width of the full-height safe column. */
    static final int CAMERA_LEFT = 810;
    /** Top edge of the camera cut-out. */
    static final int CAMERA_TOP = 664;
    /** Safe inset width/depth (px) of the rear camera cutout block (1208 - 810 = 398). */
    static final int CAMERA_CUTOUT_INSET = WIDTH - CAMERA_LEFT;
    /** Cover third-party IME density in ROTATION_0 (810 px column left of the bottom-right camera). */
    static final int IME_DENSITY_DPI_ROT0 = 340;
    /** Cover third-party IME density in ROTATION_180 / landscape (full 1208 px width, matching native SogouIME). */
    static final int IME_DENSITY_DPI_WIDE = 420;
    /** Cap on reported IME screenWidthDp/smallestScreenWidthDp so phone layout (372dp) is used instead of tablet mode. */
    static final int IME_MAX_SCREEN_WIDTH_DP = 372;
    /** Target total height of the cover keyboard in ROTATION_180/0 (matches native SogouIME: 1392 - 662 = 730 px). */
    static final int IME_TARGET_HEIGHT_PX = 730;
    /** Target candidate/toolbar header height (px) in ROTATION_180 (matches native SogouIME: 806 - 662 = 144 px). */
    static final int IME_TARGET_CANDIDATE_HEIGHT_PX = 144;
    /** Bottom chin padding (px) for third-party cover IMEs so key rows sit at the same vertical position as SogouIME. */
    static final int IME_BOTTOM_CHIN_PX = 18;
    /** Upper bound used to recognise status-bar-like top insets (native cover status bar is 114 px). */
    static final int MAX_STATUS_BAR_INSETS_TOP = 220;
    static final String ALL_DISPLAYS_CATEGORY = "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    static final String DEVICE = "bixi";
    static final String TARGET_DEVICE_NAME = "MIX Flip 2";
    static final String TARGET_SYSTEM_NAME = "HyperOS 4";
    static final String HYPEROS_MAJOR_PREFIX = "OS4.";
    static final String HYPEROS_VERSION_NAME_PREFIX = "OS4";
    static final String HYPEROS_VERSION_CODE = "4";

    private CoverDisplay() {}

    static boolean isSupportedDevice() {
        return DEVICE.equalsIgnoreCase(Build.DEVICE) || DEVICE.equalsIgnoreCase(Build.PRODUCT);
    }

    static boolean isHyperOs4Version(String incremental, String miOsVersionName, String miOsVersionCode) {
        if (incremental != null && incremental.startsWith(HYPEROS_MAJOR_PREFIX)) return true;
        if (miOsVersionName != null
                && (miOsVersionName.equals(HYPEROS_VERSION_NAME_PREFIX)
                || miOsVersionName.startsWith(HYPEROS_MAJOR_PREFIX))) {
            return true;
        }
        return HYPEROS_VERSION_CODE.equals(miOsVersionCode);
    }

    private static String readSystemProperty(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return v instanceof String s ? s.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String systemVersionSummary() {
        String inc = Build.VERSION.INCREMENTAL;
        if (inc != null && !inc.isEmpty()) return inc;
        String name = readSystemProperty("ro.mi.os.version.name");
        return name.isEmpty() ? String.valueOf(Build.VERSION.RELEASE) : name;
    }

    /**
     * MIX Flip 2 running HyperOS 4.x (Android 16/17). Every framework hook additionally validates the exact
     * method/field signatures it needs at install time and rolls everything back when one is missing, so a
     * future HyperOS 4 OTA either works or leaves the stock system untouched.
     */
    static boolean isSupportedFirmware() {
        return isSupportedDevice()
                && Build.VERSION.SDK_INT >= 36
                && isHyperOs4Version(
                        Build.VERSION.INCREMENTAL,
                        readSystemProperty("ro.mi.os.version.name"),
                        readSystemProperty("ro.mi.os.version.code"));
    }

    static boolean isOuterDimensions(int width, int height) {
        return (width == WIDTH && height == HEIGHT) || (width == HEIGHT && height == WIDTH);
    }

    static boolean isOuter(Display display) {
        if (display == null) return false;
        Display.Mode mode = display.getMode();
        return mode != null && isOuterDimensions(mode.getPhysicalWidth(), mode.getPhysicalHeight());
    }

    static boolean isOuterDisplay(Display display) {
        return isOuter(display);
    }

    static boolean isOuterActive(Display display) {
        return isOuter(display) && display.getState() == Display.STATE_ON;
    }

    static boolean isPortraitRotation(int rotation) {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180;
    }

    /** Horizontal boundary (px) between the left 1/3 notification pull zone and right 2/3 control center pull zone. */
    static float controlCenterSplitX(int displayWidth) {
        int width = displayWidth > 0 ? displayWidth : WIDTH;
        return width / 3f;
    }

    /** Returns true when rawX is in the right 2/3 top pull zone that opens the Control Center. */
    static boolean isControlCenterPull(float rawX, int displayWidth) {
        return rawX >= controlCenterSplitX(displayWidth);
    }

    /**
     * Left edge of the cover keyboard area in display coordinates.
     * - ROTATION_180 (normal cover portrait, camera at top-left Rect(0, 0, 398, 728)): 0 (full 1208 px width).
     * - ROTATION_0 (inverted cover portrait, camera at bottom-right Rect(810, 664, 1208, 1392)): 0 (left 810 px).
     * - ROTATION_90 (camera at top-right Rect(664, 0, 1392, 398)): 0 (full 1392 px width).
     * - ROTATION_270 (camera at bottom-left Rect(0, 810, 728, 1208)): 728 (right 664 px).
     */
    static int coverImeSafeLeft(int rotation) {
        return rotation == Surface.ROTATION_270 ? HEIGHT - CAMERA_TOP : 0;
    }

    /** Right edge of the cover keyboard area in display coordinates. */
    static int coverImeSafeRight(int rotation) {
        return switch (rotation) {
            case Surface.ROTATION_180 -> WIDTH;
            case Surface.ROTATION_90, Surface.ROTATION_270 -> HEIGHT;
            default -> CAMERA_LEFT;
        };
    }

    /** Usable horizontal width for the cover keyboard in the given rotation. */
    static int coverImeWidth(int rotation) {
        return coverImeSafeRight(rotation) - coverImeSafeLeft(rotation);
    }

    /** Display height in the given cover rotation. */
    static int coverImeDisplayHeight(int rotation) {
        return isPortraitRotation(rotation) ? HEIGHT : WIDTH;
    }

    /**
     * Left horizontal inset (px) for the top candidate/toolbar bar of a full-width cover keyboard so interactive
     * candidate words and toolbar icons avoid the top-left rear camera lens in ROTATION_180 (Rect(0, 0, 398, 728)).
     */
    static int coverImeCandidateLeftInset(int rotation) {
        return rotation == Surface.ROTATION_180 ? WIDTH - CAMERA_LEFT : 0;
    }

    /**
     * Maximum vertical height the bottom-anchored keyboard may occupy on the cover screen:
     * - ROTATION_180: 730 px (top at y = 662, matching native SogouIME; the top 144 px candidate bar avoids the
     *   top-left camera Rect(0, 0, 398, 728) horizontally and via its concave top-left curve, while the 586 px
     *   4-row keys area sits at y = 806..1392 well below the camera bottom 728).
     * - ROTATION_90: top-right camera bottom is 398 -> max height = 1208 - 398 = 810 (CAMERA_LEFT).
     * - ROTATION_0 / ROTATION_270: camera is avoided horizontally -> full display height.
     */
    static int coverImeMaxHeight(int rotation) {
        return switch (rotation) {
            case Surface.ROTATION_180 -> IME_TARGET_HEIGHT_PX;
            case Surface.ROTATION_90 -> CAMERA_LEFT;
            default -> coverImeDisplayHeight(rotation);
        };
    }

    /** Minimum y-coordinate (top edge) for a full-width bottom keyboard so it never overlaps a top camera cutout. */
    static int coverImeMinTop(int rotation) {
        return coverImeDisplayHeight(rotation) - coverImeMaxHeight(rotation);
    }

    static int coverImeDensityDpi(int rotation) {
        return rotation == Surface.ROTATION_0 ? IME_DENSITY_DPI_ROT0 : IME_DENSITY_DPI_WIDE;
    }

    static int coverImeScreenWidthDp(int rotation) {
        int raw = Math.round(coverImeWidth(rotation) * 160f / coverImeDensityDpi(rotation));
        return Math.min(IME_MAX_SCREEN_WIDTH_DP, raw);
    }

    static int coverImeScreenHeightDp(int rotation) {
        return Math.round(coverImeDisplayHeight(rotation) * 160f / coverImeDensityDpi(rotation));
    }

    static android.graphics.Rect coverImeSafeBounds(int rotation) {
        int left = coverImeSafeLeft(rotation);
        int right = coverImeSafeRight(rotation);
        int bottom = coverImeDisplayHeight(rotation);
        android.graphics.Rect r = new android.graphics.Rect(left, 0, right, bottom);
        r.left = left;
        r.top = 0;
        r.right = right;
        r.bottom = bottom;
        return r;
    }

    /** Rear camera cutout bounding box {left, top, right, bottom} in physical cover pixels for the given rotation. */
    static int[] outerCutoutBounds(int rotation) {
        return switch (rotation) {
            case Surface.ROTATION_180 -> new int[]{0, 0, CAMERA_CUTOUT_INSET, HEIGHT - CAMERA_TOP};
            case Surface.ROTATION_90 -> new int[]{CAMERA_TOP, 0, HEIGHT, CAMERA_CUTOUT_INSET};
            case Surface.ROTATION_270 -> new int[]{0, CAMERA_LEFT, HEIGHT - CAMERA_TOP, WIDTH};
            default -> new int[]{CAMERA_LEFT, CAMERA_TOP, WIDTH, HEIGHT};
        };
    }

    /** Rear camera safe insets {left, top, right, bottom} in physical cover pixels for the given rotation. */
    static int[] outerCutoutSafeInsets(int rotation) {
        return switch (rotation) {
            case Surface.ROTATION_180 -> new int[]{CAMERA_CUTOUT_INSET, 0, 0, 0};
            case Surface.ROTATION_90 -> new int[]{0, CAMERA_CUTOUT_INSET, 0, 0};
            case Surface.ROTATION_270 -> new int[]{0, 0, 0, CAMERA_CUTOUT_INSET};
            default -> new int[]{0, 0, CAMERA_CUTOUT_INSET, 0};
        };
    }

    /** Deterministic cover DisplayCutout fallback matching HyperOS @bind_right_cutout geometry across rotations. */
    static android.view.DisplayCutout buildOuterDisplayCutout(int rotation) {
        int[] b = outerCutoutBounds(rotation);
        int[] si = outerCutoutSafeInsets(rotation);
        android.graphics.Rect rect = new android.graphics.Rect(b[0], b[1], b[2], b[3]);
        rect.left = b[0];
        rect.top = b[1];
        rect.right = b[2];
        rect.bottom = b[3];
        android.graphics.Insets safeInsets = android.graphics.Insets.of(si[0], si[1], si[2], si[3]);
        return switch (rotation) {
            case Surface.ROTATION_180 -> new android.view.DisplayCutout(safeInsets, rect, null, null, null);
            case Surface.ROTATION_90 -> new android.view.DisplayCutout(safeInsets, null, rect, null, null);
            case Surface.ROTATION_270 -> new android.view.DisplayCutout(safeInsets, null, null, null, rect);
            default -> new android.view.DisplayCutout(safeInsets, null, null, rect, null);
        };
    }

    static boolean isOuterPortrait(Display display) {
        if (display == null) return false;
        Display.Mode mode = display.getMode();
        return mode != null
                && mode.getPhysicalWidth() == WIDTH
                && mode.getPhysicalHeight() == HEIGHT
                && isPortraitRotation(display.getRotation());
    }

    static boolean isOuterPortraitDisplay(Display display) {
        return isOuterPortrait(display);
    }

    static Display findOuterDisplay(DisplayManager dm) {
        if (dm == null) return null;
        try {
            for (Display display : dm.getDisplays()) {
                if (isOuter(display)) return display;
            }
        } catch (RuntimeException ignored) {
        }
        return findActiveOuterDisplay(dm);
    }

    static Display findActiveOuterDisplay(DisplayManager dm) {
        if (dm == null) return null;
        for (Display d : dm.getDisplays()) {
            if (isOuterActive(d)) return d;
        }
        for (Display d : dm.getDisplays(ALL_DISPLAYS_CATEGORY)) {
            if (isOuterActive(d)) return d;
        }
        return null;
    }

    /** Sends a broadcast as the calling user (used from system_server, which runs as user 0). */
    static void sendBroadcastAsUser(Context context, Intent intent) {
        if (context == null || intent == null) return;
        try {
            context.sendBroadcastAsUser(intent, Process.myUserHandle());
        } catch (Throwable t) {
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
    }
}