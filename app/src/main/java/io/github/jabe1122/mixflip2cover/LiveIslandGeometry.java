package io.github.jabe1122.mixflip2cover;

/** Physical cover coordinates; independent of logical display IDs and Android view measurement. */
final class LiveIslandGeometry {
    private static final int WIDTH = CoverDisplay.WIDTH;
    private static final int HEIGHT = CoverDisplay.HEIGHT;
    private static final int CAMERA_LEFT = CoverDisplay.CAMERA_LEFT;
    private static final int CAMERA_TOP = CoverDisplay.CAMERA_TOP;

    record Box(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private LiveIslandGeometry() {}

    /** Square card centred in the column above the camera block, below the status panel. */
    static Box compact(int orbBottom, int gap, int nativeWidth, int nativeHeight) {
        int top = Math.max(gap, orbBottom + gap), bottom = CAMERA_TOP - gap;
        int availableWidth = WIDTH - CAMERA_LEFT - gap * 2;
        if (nativeWidth <= 0 || nativeHeight <= 0 || bottom <= top || availableWidth <= 0) return null;
        int width = Math.min(availableWidth, bottom - top);
        int height = width;
        int left = CAMERA_LEFT + (WIDTH - CAMERA_LEFT - width) / 2;
        return new Box(left, top, left + width, top + height);
    }

    /** Maps a portrait box into display coordinates for {@code rotation} (Surface.ROTATION_*). */
    static Box rotated(Box b, int rotation) {
        if (b == null) return null;
        return switch (rotation) {
            case 0 -> b;
            case 1 -> new Box(b.top, WIDTH - b.right, b.bottom, WIDTH - b.left);
            case 2 -> new Box(WIDTH - b.right, HEIGHT - b.bottom, WIDTH - b.left, HEIGHT - b.top);
            case 3 -> new Box(HEIGHT - b.bottom, b.left, HEIGHT - b.top, b.right);
            default -> null;
        };
    }
}
