package io.github.jabe1122.mixflip2cover;

/** Application toolbar clearance, independent of system status-bar Insets. */
final class AppHeaderSpace {
    private AppHeaderSpace() {}

    static int paddingTop(int nativePadding, int toolbarBottom, int listTop, int toolbarHeight) {
        int overlap = toolbarBottom - listTop;
        if (toolbarHeight <= 0 || overlap <= 0 || overlap > toolbarHeight + CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
            return nativePadding;
        }
        return Math.max(nativePadding, overlap);
    }
}
