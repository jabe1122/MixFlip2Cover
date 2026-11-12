package io.github.jabe1122.mixflip2cover;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Surface;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ModuleLogicTest {

    @Test
    public void coverDisplay_targetSpecificationAndHyperOs4Detection() {
        assertEquals("bixi", CoverDisplay.DEVICE);
        assertEquals("MIX Flip 2", CoverDisplay.TARGET_DEVICE_NAME);
        assertEquals("HyperOS 4", CoverDisplay.TARGET_SYSTEM_NAME);

        // Valid HyperOS 4 markers
        assertTrue(CoverDisplay.isHyperOs4Version("OS4.0.0.11.XOHCNXM", "", ""));
        assertTrue(CoverDisplay.isHyperOs4Version("", "OS4", ""));
        assertTrue(CoverDisplay.isHyperOs4Version("", "OS4.0", ""));
        assertTrue(CoverDisplay.isHyperOs4Version(null, null, "4"));

        // Non-HyperOS 4 markers (e.g. HyperOS 2 / HyperOS 3 / OS40 / AOSP)
        assertFalse(CoverDisplay.isHyperOs4Version("OS3.0.5.0.WOHCNXM", "OS3.0", "3"));
        assertFalse(CoverDisplay.isHyperOs4Version("OS2.0.100.0.VOHCNXM", "OS2.0", "2"));
        assertFalse(CoverDisplay.isHyperOs4Version("", "OS40.0", ""));
        assertFalse(CoverDisplay.isHyperOs4Version("", "", ""));
        assertFalse(CoverDisplay.isHyperOs4Version(null, null, null));
    }

    @Test
    public void coverDisplay_dimensionsAndRotation() {
        assertTrue(CoverDisplay.isOuterDimensions(1208, 1392));
        assertTrue(CoverDisplay.isOuterDimensions(1392, 1208));
        assertFalse(CoverDisplay.isOuterDimensions(1224, 2912));
        assertFalse(CoverDisplay.isOuterDimensions(0, 0));
        assertFalse(CoverDisplay.isOuterDimensions(-1208, 1392));

        assertTrue(CoverDisplay.isPortraitRotation(Surface.ROTATION_0));
        assertTrue(CoverDisplay.isPortraitRotation(Surface.ROTATION_180));
        assertFalse(CoverDisplay.isPortraitRotation(Surface.ROTATION_90));
        assertFalse(CoverDisplay.isPortraitRotation(Surface.ROTATION_270));

        // ROTATION_180 (normal cover portrait, camera at top-left Rect(0, 0, 398, 728)):
        // full 1208 px width at bottom, 730 px height (minTop 662 px, matching native SogouIME),
        // candidate bar inset 398 px from left to avoid the top-left camera
        assertEquals(0, CoverDisplay.coverImeSafeLeft(Surface.ROTATION_180));
        assertEquals(1208, CoverDisplay.coverImeSafeRight(Surface.ROTATION_180));
        assertEquals(1208, CoverDisplay.coverImeWidth(Surface.ROTATION_180));
        assertEquals(1392, CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_180));
        assertEquals(398, CoverDisplay.coverImeCandidateLeftInset(Surface.ROTATION_180));
        assertEquals(730, CoverDisplay.coverImeMaxHeight(Surface.ROTATION_180));
        assertEquals(662, CoverDisplay.coverImeMinTop(Surface.ROTATION_180));
        assertEquals(420, CoverDisplay.coverImeDensityDpi(Surface.ROTATION_180));
        assertEquals(372, CoverDisplay.coverImeScreenWidthDp(Surface.ROTATION_180));

        // ROTATION_0 (inverted cover portrait, camera at bottom-right Rect(810, 664, 1208, 1392)):
        // left 810 px column avoids camera horizontally
        assertEquals(0, CoverDisplay.coverImeSafeLeft(Surface.ROTATION_0));
        assertEquals(810, CoverDisplay.coverImeSafeRight(Surface.ROTATION_0));
        assertEquals(810, CoverDisplay.coverImeWidth(Surface.ROTATION_0));
        assertEquals(1392, CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_0));
        assertEquals(0, CoverDisplay.coverImeCandidateLeftInset(Surface.ROTATION_0));
        assertEquals(1392, CoverDisplay.coverImeMaxHeight(Surface.ROTATION_0));
        assertEquals(0, CoverDisplay.coverImeMinTop(Surface.ROTATION_0));
        assertEquals(340, CoverDisplay.coverImeDensityDpi(Surface.ROTATION_0));
        assertEquals(372, CoverDisplay.coverImeScreenWidthDp(Surface.ROTATION_0));
        assertEquals(655, CoverDisplay.coverImeScreenHeightDp(Surface.ROTATION_0));

        // ROTATION_90 (camera at top-right Rect(664, 0, 1392, 398)): full 1392 px width at bottom, minTop 398 px
        assertEquals(0, CoverDisplay.coverImeSafeLeft(Surface.ROTATION_90));
        assertEquals(1392, CoverDisplay.coverImeSafeRight(Surface.ROTATION_90));
        assertEquals(1392, CoverDisplay.coverImeWidth(Surface.ROTATION_90));
        assertEquals(1208, CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_90));
        assertEquals(810, CoverDisplay.coverImeMaxHeight(Surface.ROTATION_90));
        assertEquals(398, CoverDisplay.coverImeMinTop(Surface.ROTATION_90));

        // ROTATION_270 (camera at bottom-left Rect(0, 810, 728, 1208)): right 664 px column [728, 1392]
        assertEquals(728, CoverDisplay.coverImeSafeLeft(Surface.ROTATION_270));
        assertEquals(1392, CoverDisplay.coverImeSafeRight(Surface.ROTATION_270));
        assertEquals(664, CoverDisplay.coverImeWidth(Surface.ROTATION_270));
        assertEquals(1208, CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_270));

        // Top pull-down split: left 1/3 (< 402.67 px) opens Notification Shade, right 2/3 (>= 402.67 px) opens Control Center
        assertEquals(1208 / 3f, CoverDisplay.controlCenterSplitX(1208), 0.01f);
        assertEquals(1208 / 3f, CoverDisplay.controlCenterSplitX(0), 0.01f);
        assertFalse(CoverDisplay.isControlCenterPull(0f, 1208));
        assertFalse(CoverDisplay.isControlCenterPull(200f, 1208));
        assertFalse(CoverDisplay.isControlCenterPull(402f, 1208));
        assertTrue(CoverDisplay.isControlCenterPull(403f, 1208));
        assertTrue(CoverDisplay.isControlCenterPull(604f, 1208));
        assertTrue(CoverDisplay.isControlCenterPull(810f, 1208));
        assertTrue(CoverDisplay.isControlCenterPull(1207f, 1208));
    }

    @Test
    public void frameworkPolicy_coverDisplayAndEligibility() {
        assertTrue(FrameworkPolicy.coverDisplay("local:4630946545580055170", 1208, 1392));
        assertTrue(FrameworkPolicy.coverDisplay("local:1", 1392, 1208));
        assertFalse(FrameworkPolicy.coverDisplay(null, 1208, 1392));
        assertFalse(FrameworkPolicy.coverDisplay("virtual:cast", 1208, 1392));
        assertFalse(FrameworkPolicy.coverDisplay("local:0", 1224, 2912));

        // Eligible application window on cover display
        assertTrue(FrameworkPolicy.eligible("local:1", 1208, 1392, 1, true, 1));
        assertTrue(FrameworkPolicy.eligible("local:1", 1208, 1392, 2, true, 0));
        assertTrue(FrameworkPolicy.eligible("local:1", 1208, 1392, 1000, true, 6));

        // Non-activity or system window or unsupported windowing mode
        assertFalse(FrameworkPolicy.eligible("local:1", 1208, 1392, 1, false, 1));
        assertFalse(FrameworkPolicy.eligible("local:1", 1208, 1392, 2000, true, 1));
        assertFalse(FrameworkPolicy.eligible("local:1", 1208, 1392, 1, true, 5));
    }

    @Test
    public void frameworkPolicy_filteredTypesAndNativeFullscreen() {
        int statusBars = 1;
        int navBars = 2;
        assertEquals(navBars, FrameworkPolicy.filteredTypes(statusBars | navBars, statusBars));
        assertEquals(0, FrameworkPolicy.filteredTypes(statusBars, statusBars));

        assertFalse(FrameworkPolicy.nativeFullscreen(0, true, true)); // home is never treated as fullscreen app
        assertTrue(FrameworkPolicy.nativeFullscreen(statusBars, true, false)); // FLAG_FULLSCREEN set
        assertTrue(FrameworkPolicy.nativeFullscreen(navBars, false, false)); // statusBar not requested
        assertFalse(FrameworkPolicy.nativeFullscreen(statusBars | navBars, false, false)); // normal app
    }

    @Test
    public void liveIslandGeometry_compactAndRotated() {
        LiveIslandGeometry.Box box = LiveIslandGeometry.compact(240, 18, 300, 300);
        assertNotNull(box);
        assertTrue(box.left() >= CoverDisplay.CAMERA_LEFT);
        assertTrue(box.right() <= CoverDisplay.WIDTH);
        assertTrue(box.top() >= 258);
        assertTrue(box.bottom() <= CoverDisplay.CAMERA_TOP - 18);
        assertEquals(box.width(), box.height());

        // Invalid parameters return null
        assertNull(LiveIslandGeometry.compact(240, 18, 0, 300));
        assertNull(LiveIslandGeometry.compact(240, 18, 300, -1));
        assertNull(LiveIslandGeometry.compact(CoverDisplay.CAMERA_TOP, 18, 300, 300));
        assertNull(LiveIslandGeometry.compact(240, 300, 300, 300));

        // Rotations preserve dimensions and handle null/invalid rotation safely
        assertNull(LiveIslandGeometry.rotated(null, 0));
        assertNull(LiveIslandGeometry.rotated(box, 99));
        assertEquals(box, LiveIslandGeometry.rotated(box, 0));

        LiveIslandGeometry.Box r180 = LiveIslandGeometry.rotated(box, 2);
        assertNotNull(r180);
        assertEquals(box.width(), r180.width());
        assertEquals(box.height(), r180.height());
        assertEquals(CoverDisplay.WIDTH - box.right(), r180.left());
        assertEquals(CoverDisplay.HEIGHT - box.bottom(), r180.top());
    }

    @Test
    public void appHeaderSpace_paddingTopEdgeCases() {
        assertEquals(20, AppHeaderSpace.paddingTop(20, 100, 100, 56)); // zero overlap
        assertEquals(20, AppHeaderSpace.paddingTop(20, 120, 100, 0));  // invalid toolbarHeight
        assertEquals(40, AppHeaderSpace.paddingTop(20, 140, 100, 56)); // 40px overlap > 20px native
        assertEquals(50, AppHeaderSpace.paddingTop(50, 140, 100, 56)); // nativePadding already larger
        assertEquals(20, AppHeaderSpace.paddingTop(20, 300, 100, 56)); // overlap exceeds toolbar + 30px
    }

    @Test
    public void mainActivity_resolveDependencies() {
        Map<String, Boolean> base = new HashMap<>();
        base.put(ModuleConfig.KEY_PANEL, true);
        base.put(ModuleConfig.KEY_LIVE_ISLAND, true);
        base.put(ModuleConfig.KEY_IMMERSIVE, true);
        base.put(ModuleConfig.KEY_UNIVERSAL_COMPAT, true);
        base.put(ModuleConfig.KEY_BATTERY_NUMBER, true);
        base.put(ModuleConfig.KEY_DUAL_SIM, true);

        // Turning off Live Island must also turn off Panel and its children (Battery Number & Dual SIM)
        Map<String, Boolean> offIsland = MainActivity.resolveDependencies(base, ModuleConfig.KEY_LIVE_ISLAND, false);
        assertEquals(Boolean.FALSE, offIsland.get(ModuleConfig.KEY_LIVE_ISLAND));
        assertEquals(Boolean.FALSE, offIsland.get(ModuleConfig.KEY_PANEL));
        assertEquals(Boolean.FALSE, offIsland.get(ModuleConfig.KEY_BATTERY_NUMBER));
        assertEquals(Boolean.FALSE, offIsland.get(ModuleConfig.KEY_DUAL_SIM));

        // Turning on Battery Number when Panel & Island are off must turn on Panel and Live Island
        Map<String, Boolean> allOff = new HashMap<>();
        allOff.put(ModuleConfig.KEY_PANEL, false);
        allOff.put(ModuleConfig.KEY_LIVE_ISLAND, false);
        allOff.put(ModuleConfig.KEY_IMMERSIVE, false);
        allOff.put(ModuleConfig.KEY_UNIVERSAL_COMPAT, false);
        allOff.put(ModuleConfig.KEY_BATTERY_NUMBER, false);
        allOff.put(ModuleConfig.KEY_DUAL_SIM, false);

        Map<String, Boolean> onBattery = MainActivity.resolveDependencies(allOff, ModuleConfig.KEY_BATTERY_NUMBER, true);
        assertEquals(Boolean.TRUE, onBattery.get(ModuleConfig.KEY_BATTERY_NUMBER));
        assertEquals(Boolean.TRUE, onBattery.get(ModuleConfig.KEY_PANEL));
        assertEquals(Boolean.TRUE, onBattery.get(ModuleConfig.KEY_LIVE_ISLAND));

        // Turning on Immersive turns on Universal Compat; turning off Immersive turns off Universal Compat
        Map<String, Boolean> onImmersive = MainActivity.resolveDependencies(allOff, ModuleConfig.KEY_IMMERSIVE, true);
        assertEquals(Boolean.TRUE, onImmersive.get(ModuleConfig.KEY_IMMERSIVE));
        assertEquals(Boolean.TRUE, onImmersive.get(ModuleConfig.KEY_UNIVERSAL_COMPAT));

        Map<String, Boolean> offImmersive = MainActivity.resolveDependencies(base, ModuleConfig.KEY_IMMERSIVE, false);
        assertEquals(Boolean.FALSE, offImmersive.get(ModuleConfig.KEY_IMMERSIVE));
        assertEquals(Boolean.FALSE, offImmersive.get(ModuleConfig.KEY_UNIVERSAL_COMPAT));
    }

    @Test
    public void appCompatibility_supportsPackageFiltering() {
        assertFalse(AppCompatibility.supports(null));
        assertFalse(AppCompatibility.supports(""));
        assertFalse(AppCompatibility.supports("android"));
        assertFalse(AppCompatibility.supports("com.android.systemui"));
        assertFalse(AppCompatibility.supports("com.miui.home"));
        assertFalse(AppCompatibility.supports("io.github.jabe1122.mixflip2cover"));

        assertTrue(AppCompatibility.supports("com.miui.fliphome"));
        assertTrue(AppCompatibility.supports("com.xiaomi.calendar"));
        assertTrue(AppCompatibility.supports("com.tencent.mm"));
        assertTrue(AppCompatibility.supports("com.netease.cloudmusic"));
        assertTrue(AppCompatibility.supports(AppCompatibility.VOICEASSIST_PKG));

        assertTrue(AppCompatibility.hasDedicatedInProcessAdapter("com.tencent.mm"));
        assertTrue(AppCompatibility.hasDedicatedInProcessAdapter("com.tencent.mobileqq"));
        assertTrue(AppCompatibility.hasDedicatedInProcessAdapter(AppCompatibility.VOICEASSIST_PKG));
        assertFalse(AppCompatibility.hasDedicatedInProcessAdapter("com.umetrip.android.msky.app"));

        assertTrue(AppCompatibility.isPackerClassName("com.stub.StubApp"));
        assertTrue(AppCompatibility.isPackerClassName("com.secneo.apkwrapper.AW"));
        assertTrue(AppCompatibility.isPackerClassName("s.h.e.l.l.S"));
        assertFalse(AppCompatibility.isPackerClassName("android.app.Application"));
        assertFalse(AppCompatibility.isPackerClassName(null));

        assertTrue(AppCompatibility.isPackerLibraryName("libjiagu_64.so"));
        assertTrue(AppCompatibility.isPackerLibraryName("libDexHelper.so"));
        assertFalse(AppCompatibility.isPackerLibraryName("libc++_shared.so"));
        assertFalse(AppCompatibility.isPackerLibraryName("libnesec.so"));

        android.content.pm.ApplicationInfo packedInfo = new android.content.pm.ApplicationInfo();
        packedInfo.packageName = "com.umetrip.android.msky.app";
        packedInfo.className = "com.stub.StubApp";
        assertTrue(AppCompatibility.isPackedOrProtectedApp(packedInfo));

        android.content.pm.ApplicationInfo normalInfo = new android.content.pm.ApplicationInfo();
        normalInfo.packageName = "com.example.normal";
        normalInfo.className = "com.example.normal.App";
        assertFalse(AppCompatibility.isPackedOrProtectedApp(normalInfo));
    }

    @Test
    public void appCompatibility_metricsAndConfigClassification() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        dm.widthPixels = 810;
        dm.heightPixels = 1392;
        dm.densityDpi = 340;
        assertTrue(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertTrue(AppCompatibility.isCoverSizedMetrics(dm));

        dm.widthPixels = 1208;
        dm.heightPixels = 1392;
        dm.densityDpi = 420;
        assertTrue(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertTrue(AppCompatibility.isCoverSizedMetrics(dm));

        dm.widthPixels = 1208;
        dm.heightPixels = 1392;
        dm.densityDpi = 520;
        assertTrue(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertTrue(AppCompatibility.isCoverSizedMetrics(dm));

        // Cover-scaled system metrics (370 dpi) and ProcessCompatController 0.8x bounds (1011x1740 @ 416 dpi)
        // are cover-sized (so restoreOverrides replaces them on unfold) but not module-overridden
        dm.densityDpi = 370;
        assertFalse(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertTrue(AppCompatibility.isCoverSizedMetrics(dm));

        dm.widthPixels = 1011;
        dm.heightPixels = 1740;
        dm.densityDpi = 416;
        assertFalse(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertTrue(AppCompatibility.isCoverSizedMetrics(dm));

        // Inner screen metrics (1224x2912 @ 460 dpi) are neither cover-sized nor module-overridden
        dm.widthPixels = 1224;
        dm.heightPixels = 2912;
        dm.densityDpi = 460;
        assertFalse(AppCompatibility.isModuleOverriddenMetrics(dm));
        assertFalse(AppCompatibility.isCoverSizedMetrics(dm));

        android.content.res.Configuration cfg = new android.content.res.Configuration();
        cfg.densityDpi = 420;
        cfg.screenWidthDp = 372;
        cfg.screenHeightDp = 530;
        assertTrue(AppCompatibility.isModuleOverriddenConfig(cfg));
        assertTrue(AppCompatibility.isCoverSizedConfig(cfg));

        cfg.densityDpi = 340;
        cfg.screenWidthDp = 372;
        cfg.screenHeightDp = 655;
        assertTrue(AppCompatibility.isModuleOverriddenConfig(cfg));
        assertTrue(AppCompatibility.isCoverSizedConfig(cfg));

        cfg.densityDpi = 520;
        cfg.screenWidthDp = 372;
        cfg.screenHeightDp = 428;
        assertTrue(AppCompatibility.isModuleOverriddenConfig(cfg));
        assertTrue(AppCompatibility.isCoverSizedConfig(cfg));

        // Inner screen configuration (1224x2912 @ 460 dpi -> ~425x1012 dp)
        cfg.densityDpi = 460;
        cfg.screenWidthDp = 425;
        cfg.screenHeightDp = 1012;
        assertFalse(AppCompatibility.isModuleOverriddenConfig(cfg));
        assertFalse(AppCompatibility.isCoverSizedConfig(cfg));
    }

    @Test
    public void appCompatibility_overrideAndRestorePreservesInnerScreenOnUnfold() {
        // 1. Cover-to-cover restoration (leaving native cover activity back to scaled cover activity)
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        dm.widthPixels = 1208;
        dm.heightPixels = 1392;
        dm.densityDpi = 370;
        dm.density = 370 / 160f;
        dm.scaledDensity = dm.density;

        AppCompatibility.overrideMetrics(dm, CoverDisplay.WIDTH, CoverDisplay.HEIGHT, CoverDisplay.DENSITY_DPI);
        assertEquals(520, dm.densityDpi);
        AppCompatibility.restoreOverrides(true);
        assertEquals(1208, dm.widthPixels);
        assertEquals(1392, dm.heightPixels);
        assertEquals(370, dm.densityDpi);

        // 2. Cover-to-inner unfold when Android has already updated dm in place to inner screen (1224x2912 @ 460 dpi)
        AppCompatibility.overrideMetrics(dm, CoverDisplay.WIDTH, CoverDisplay.HEIGHT, CoverDisplay.DENSITY_DPI);
        assertEquals(520, dm.densityDpi);
        dm.widthPixels = 1224;
        dm.heightPixels = 2912;
        dm.densityDpi = 460;
        dm.density = 460 / 160f;
        AppCompatibility.restoreOverrides(false);
        assertEquals(1224, dm.widthPixels);
        assertEquals(2912, dm.heightPixels);
        assertEquals(460, dm.densityDpi);

        // 3. Cover-to-inner unfold when getDisplayMetrics() hook runs after Android updates dm to inner screen
        // but before outerProcess flips to false: snapshot updates to inner screen and restores cleanly
        dm.widthPixels = 1208;
        dm.heightPixels = 1392;
        dm.densityDpi = 370;
        dm.density = 370 / 160f;
        AppCompatibility.overrideMetrics(dm, 810, 1392, 340);
        // Simulate Android updating dm to inner screen on unfold while imeCoverActive is still true
        dm.widthPixels = 1224;
        dm.heightPixels = 2912;
        dm.densityDpi = 460;
        dm.density = 460 / 160f;
        AppCompatibility.overrideMetrics(dm, 810, 1392, 340);
        assertEquals(810, dm.widthPixels);
        AppCompatibility.restoreOverrides(false);
        assertEquals(1224, dm.widthPixels);
        assertEquals(2912, dm.heightPixels);
        assertEquals(460, dm.densityDpi);
    }

    @Test
    public void coverContinuityWhitelist_allowsAllPackagesByDefault() {
        assertTrue(ModuleConfig.allowAllCoverApps());
        assertTrue(ModuleConfig.directOpenCoverNotifications());
        SystemUiEntry.CoverContinuityWhitelist list = new SystemUiEntry.CoverContinuityWhitelist(
                java.util.List.of("com.tencent.mm"));
        assertTrue(list.contains("com.tencent.mm"));
        assertTrue(list.contains("com.eg.android.AlipayGphone"));
        assertTrue(list.contains("com.xingin.xhs"));
    }

    @Test
    public void coverNotificationTop_compactsCoverHeaderAndHeadsUpOnlyOnOuterScreen() {
        // Cover notification header height (245px native) trims 65px top blank space -> 180px
        assertEquals(180f, CoverNotificationTop.adjustCombinedHeaderHeight(245f, true, true, false), 0.01f);
        // Control center header layout remains untouched (245px)
        assertEquals(245f, CoverNotificationTop.adjustCombinedHeaderHeight(245f, true, true, true), 0.01f);
        // Inner screen or disabled state remains untouched
        assertEquals(245f, CoverNotificationTop.adjustCombinedHeaderHeight(245f, true, false, false), 0.01f);
        assertEquals(245f, CoverNotificationTop.adjustCombinedHeaderHeight(245f, false, true, false), 0.01f);

        // Cover heads-up top inset clamps from 138px (114px status bar + padding) to 18px
        assertEquals(CoverGeometry.EDGE_INSET, CoverNotificationTop.adjustHeadsUpInset(138, true, true));
        assertEquals(138, CoverNotificationTop.adjustHeadsUpInset(138, true, false));
        assertEquals(138, CoverNotificationTop.adjustHeadsUpInset(138, false, true));

        // Live fold/rotation dimension check rejects inner screen and cover landscape
        assertTrue(CoverNotificationTop.isOuterPortraitMetrics(
                1208, 1392, android.content.res.Configuration.ORIENTATION_PORTRAIT));
        assertFalse(CoverNotificationTop.isOuterPortraitMetrics(
                1392, 1208, android.content.res.Configuration.ORIENTATION_LANDSCAPE));
        assertFalse(CoverNotificationTop.isOuterPortraitMetrics(
                1224, 2912, android.content.res.Configuration.ORIENTATION_PORTRAIT));
    }

    @Test
    public void panelMotion_pullDownSplitGatesAndContinuousDeltaX() {
        // Only starts from top status bar strip when neither panel is already expanded
        assertTrue(PanelMotion.shouldStartPullDownSplit(true, true, false, false, 12f));
        assertTrue(PanelMotion.shouldStartPullDownSplit(true, true, false, false, 114f));
        assertFalse(PanelMotion.shouldStartPullDownSplit(true, true, false, false, 115f));
        assertFalse(PanelMotion.shouldStartPullDownSplit(true, true, false, false, 520f));
        assertFalse(PanelMotion.shouldStartPullDownSplit(true, true, true, false, 20f));
        assertFalse(PanelMotion.shouldStartPullDownSplit(true, true, false, true, 20f));
        assertFalse(PanelMotion.shouldStartPullDownSplit(true, false, false, false, 20f));

        // Left 1/3 (< 402.67px) has zero artificial X shift
        assertEquals(0f, PanelMotion.computePullDownDeltaX(180f, 1208), 0.01f);
        assertEquals(0f, PanelMotion.computePullDownDeltaX(400f, 1208), 0.01f);

        // Middle-right [403..700] shifts smoothly into Control Center zone (>= 701px)
        float d420 = PanelMotion.computePullDownDeltaX(420f, 1208);
        assertEquals(701f, 420f + d420, 0.01f);
        float d600 = PanelMotion.computePullDownDeltaX(600f, 1208);
        assertEquals(701f, 600f + d600, 0.01f);

        // Far-right (>= 701px) is already in Control Center zone: zero pull-back offset!
        assertEquals(0f, PanelMotion.computePullDownDeltaX(850f, 1208), 0.01f);
        assertEquals(0f, PanelMotion.computePullDownDeltaX(1080f, 1208), 0.01f);

        // Landscape cover (1392px wide): threshold 464px, minControlX = round(1392*0.58) = 807px
        assertEquals(0f, PanelMotion.computePullDownDeltaX(320f, 1392), 0.01f);
        assertEquals(807f, 500f + PanelMotion.computePullDownDeltaX(500f, 1392), 0.01f);
        assertEquals(0f, PanelMotion.computePullDownDeltaX(1100f, 1392), 0.01f);
    }

    @Test
    public void systemBarMask_andModLogTagCoverage() {
        assertEquals(1208, SystemBarMask.coverTouchableTopWidth(android.view.Surface.ROTATION_0));
        assertEquals(1208, SystemBarMask.coverTouchableTopWidth(android.view.Surface.ROTATION_180));
        assertEquals(1392, SystemBarMask.coverTouchableTopWidth(android.view.Surface.ROTATION_90));
        assertEquals(1392, SystemBarMask.coverTouchableTopWidth(android.view.Surface.ROTATION_270));

        java.util.Set<String> tags = new java.util.HashSet<>(java.util.Arrays.asList(ModLog.LOGCAT_TAGS));
        assertTrue(tags.contains("MF2C.NotifTop:V"));
        assertTrue(tags.contains("MF2C.Panel:V"));
        assertTrue(tags.contains("MF2C.Gesture:V"));
        assertTrue(tags.contains("MF2C.Mask:V"));
        assertTrue(tags.contains("MF2C.WidgetFit:V"));
        assertEquals("*:S", ModLog.LOGCAT_TAGS[ModLog.LOGCAT_TAGS.length - 1]);
    }

    @Test
    public void voiceAssistCoverCutout_restoresOuterCutoutAndClearsCameraAndOrb() {
        assertEquals(398, CoverDisplay.CAMERA_CUTOUT_INSET);
        assertTrue(AppCompatibility.ADAPTED_APP_SCOPE.contains(AppCompatibility.VOICEASSIST_PKG));

        // Cutout bounds & safe insets match HyperOS @bind_right_cutout across all 4 rotations
        assertArrayEquals(new int[]{810, 664, 1208, 1392}, CoverDisplay.outerCutoutBounds(Surface.ROTATION_0));
        assertArrayEquals(new int[]{0, 0, 398, 0}, CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_0));

        assertArrayEquals(new int[]{0, 0, 398, 728}, CoverDisplay.outerCutoutBounds(Surface.ROTATION_180));
        assertArrayEquals(new int[]{398, 0, 0, 0}, CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_180));

        assertArrayEquals(new int[]{664, 0, 1392, 398}, CoverDisplay.outerCutoutBounds(Surface.ROTATION_90));
        assertArrayEquals(new int[]{0, 398, 0, 0}, CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_90));

        assertArrayEquals(new int[]{0, 810, 728, 1208}, CoverDisplay.outerCutoutBounds(Surface.ROTATION_270));
        assertArrayEquals(new int[]{0, 0, 0, 398}, CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_270));

        // Cutout exposure gate: active only for floating overlay on outer display when enabled
        assertTrue(AppCompatibility.shouldExposeVoiceAssistCoverCutout(true, true, false, true, true));
        assertFalse(AppCompatibility.shouldExposeVoiceAssistCoverCutout(true, false, false, true, true)); // inner display
        assertFalse(AppCompatibility.shouldExposeVoiceAssistCoverCutout(true, true, true, true, true));  // 810px compat Activity
        assertFalse(AppCompatibility.shouldExposeVoiceAssistCoverCutout(true, true, false, false, true)); // global off
        assertFalse(AppCompatibility.shouldExposeVoiceAssistCoverCutout(true, true, false, true, false)); // per-app off
        assertFalse(AppCompatibility.shouldExposeVoiceAssistCoverCutout(false, true, false, true, true)); // other package

        // Downstream Super XiaoAi capsule_ready (696px wide, 52px outer margin) placement check:
        int capsuleWidth = 696;
        int baseEdgePx = 52;
        int[] rot0Insets = CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_0);
        int rot0Left = Math.max(baseEdgePx, rot0Insets[0])
                + (CoverDisplay.WIDTH - Math.max(baseEdgePx, rot0Insets[0]) - Math.max(baseEdgePx, rot0Insets[2]) - capsuleWidth) / 2;
        int rot0Right = rot0Left + capsuleWidth;
        assertEquals(83, rot0Left);
        assertEquals(779, rot0Right);
        assertTrue(rot0Right < CoverDisplay.CAMERA_LEFT);          // clears [810..1208] camera column
        assertTrue(rot0Right < CoverGeometry.PANEL_CENTER_X - 88); // clears Status Orb [921..1097]

        int[] rot180Insets = CoverDisplay.outerCutoutSafeInsets(Surface.ROTATION_180);
        int rot180Left = Math.max(baseEdgePx, rot180Insets[0])
                + (CoverDisplay.WIDTH - Math.max(baseEdgePx, rot180Insets[0]) - Math.max(baseEdgePx, rot180Insets[2]) - capsuleWidth) / 2;
        int rot180Right = rot180Left + capsuleWidth;
        assertEquals(429, rot180Left);
        assertEquals(1125, rot180Right);
        assertTrue(rot180Left > CoverDisplay.CAMERA_CUTOUT_INSET); // clears [0..398] top-left camera
        assertTrue(rot180Right < CoverDisplay.WIDTH);
    }

    @Test
    public void appCompatibility_weakIdentityMapRestoresMoreThan64OverriddenMetricsAndConfigs() throws Exception {
        final int count = 100; // > old 64-entry hard cap
        android.util.DisplayMetrics[] metricsList = new android.util.DisplayMetrics[count];
        for (int i = 0; i < count; i++) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            dm.widthPixels = 1208;
            dm.heightPixels = 1392;
            dm.densityDpi = 370;
            dm.density = 370 / 160f;
            dm.scaledDensity = dm.density * (1.0f + (i % 3) * 0.1f);
            metricsList[i] = dm;
            AppCompatibility.overrideMetrics(dm, 810, 1392, 340);
            assertEquals(810, dm.widthPixels);
            assertEquals(340, dm.densityDpi);
        }

        // Every single one of the 100 instances (including 65th..100th) must restore cleanly
        AppCompatibility.restoreOverrides(true);
        for (int i = 0; i < count; i++) {
            assertEquals("metrics[" + i + "] width", 1208, metricsList[i].widthPixels);
            assertEquals("metrics[" + i + "] height", 1392, metricsList[i].heightPixels);
            assertEquals("metrics[" + i + "] dpi", 370, metricsList[i].densityDpi);
        }

        // Also verify >64 Configuration snapshots are retained without silent cap truncation
        android.content.res.Configuration[] configs = new android.content.res.Configuration[count];
        for (int i = 0; i < count; i++) {
            android.content.res.Configuration cfg = new android.content.res.Configuration();
            cfg.densityDpi = 460;
            cfg.screenWidthDp = 425;
            cfg.screenHeightDp = 1012;
            configs[i] = cfg;
            AppCompatibility.rememberConfig(cfg);
        }
        java.lang.reflect.Field mapField = AppCompatibility.class.getDeclaredField("overriddenOriginals");
        mapField.setAccessible(true);
        Object weakMap = mapField.get(null);
        java.lang.reflect.Method snapshotMethod = weakMap.getClass().getDeclaredMethod("entriesSnapshot");
        snapshotMethod.setAccessible(true);
        java.util.List<?> entries = (java.util.List<?>) snapshotMethod.invoke(weakMap);
        java.util.Set<Object> keys = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object obj : entries) {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) obj;
            keys.add(entry.getKey());
        }
        for (int i = 0; i < count; i++) {
            assertTrue("configs[" + i + "] must remain tracked beyond 64 entries", keys.contains(configs[i]));
        }
    }

    @Test
    public void launcherPullDown_rotationAwareDimensionsAndPortraitCameraColumn() {
        // Portrait rotations (0 and 180) keep 1208x1392 logical cover viewport
        assertEquals(1208, LauncherPullDown.logicalDisplayWidth(Surface.ROTATION_0));
        assertEquals(1392, LauncherPullDown.logicalDisplayHeight(Surface.ROTATION_0));
        assertEquals(1208, LauncherPullDown.logicalDisplayWidth(Surface.ROTATION_180));
        assertEquals(1392, LauncherPullDown.logicalDisplayHeight(Surface.ROTATION_180));

        // Landscape rotations (90 and 270) transpose logical cover viewport to 1392x1208
        assertEquals(1392, LauncherPullDown.logicalDisplayWidth(Surface.ROTATION_90));
        assertEquals(1208, LauncherPullDown.logicalDisplayHeight(Surface.ROTATION_90));
        assertEquals(1392, LauncherPullDown.logicalDisplayWidth(Surface.ROTATION_270));
        assertEquals(1208, LauncherPullDown.logicalDisplayHeight(Surface.ROTATION_270));

        // ROTATION_0: camera/widget column is right [810, 1208); left app grid [0, 810) is NOT camera column
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(0f, Surface.ROTATION_0));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(400f, Surface.ROTATION_0));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(809.9f, Surface.ROTATION_0));
        assertTrue(LauncherPullDown.isInPortraitCameraColumn(810f, Surface.ROTATION_0));
        assertTrue(LauncherPullDown.isInPortraitCameraColumn(1150f, Surface.ROTATION_0));

        // ROTATION_180: camera/widget column is left [0, 398); right app grid [398, 1208) must NOT hijack list scroll
        assertTrue(LauncherPullDown.isInPortraitCameraColumn(0f, Surface.ROTATION_180));
        assertTrue(LauncherPullDown.isInPortraitCameraColumn(200f, Surface.ROTATION_180));
        assertTrue(LauncherPullDown.isInPortraitCameraColumn(397.9f, Surface.ROTATION_180));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(398f, Surface.ROTATION_180));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(810f, Surface.ROTATION_180));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(1150f, Surface.ROTATION_180));

        // Landscape rotations (90 and 270) have no portrait camera column shortcut
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(100f, Surface.ROTATION_90));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(1000f, Surface.ROTATION_90));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(100f, Surface.ROTATION_270));
        assertFalse(LauncherPullDown.isInPortraitCameraColumn(1000f, Surface.ROTATION_270));
    }

    @Test
    public void pinnedAppsWidget_parseSerializeAndPackageRelevanceFiltering() throws Exception {
        try {
            // Order preservation, deduplication, FlipHome exclusion, whitespace trimming, and 4-app cap
            java.util.List<String> parsed = ModuleConfig.parsePinnedApps(
                    " com.tencent.mm , com.miui.fliphome, com.android.settings, com.tencent.mm, "
                            + "com.netease.cloudmusic, com.sankuai.meituan, com.xiaomi.shop ");
            assertEquals(
                    java.util.List.of(
                            "com.tencent.mm",
                            "com.android.settings",
                            "com.netease.cloudmusic",
                            "com.sankuai.meituan"),
                    parsed);
            assertEquals(
                    "com.tencent.mm,com.android.settings,com.netease.cloudmusic,com.sankuai.meituan",
                    ModuleConfig.serializePinnedApps(parsed));
            assertTrue(ModuleConfig.parsePinnedApps(null).isEmpty());
            assertTrue(ModuleConfig.parsePinnedApps(" , com.miui.fliphome , ").isEmpty());
            assertEquals("", ModuleConfig.serializePinnedApps(java.util.Collections.emptyList()));

            // Package relevance filtering for ACTION_PACKAGE_CHANGED spam suppression
            java.lang.reflect.Method isRelevant = PinnedAppsWidget.class.getDeclaredMethod(
                    "isPackageRelevantToWidget", String.class);
            isRelevant.setAccessible(true);

            ModuleConfig.overridePinnedWidget(true, "com.tencent.mm,com.android.settings");
            assertTrue(ModuleConfig.hasPinnedWidgetApps());
            assertEquals(Boolean.TRUE, isRelevant.invoke(null, "com.tencent.mm"));
            assertEquals(Boolean.TRUE, isRelevant.invoke(null, "com.android.settings"));
            assertEquals(Boolean.FALSE, isRelevant.invoke(null, "com.xiaomi.joyose"));
            assertEquals(Boolean.FALSE, isRelevant.invoke(null, ""));
            assertEquals(Boolean.FALSE, isRelevant.invoke(null, new Object[]{null}));
        } finally {
            ModuleConfig.overridePinnedWidget(false, "");
        }
    }

    @Test
    public void panelMotion_sequentialDualHookTouchStreamPreservesAndResetsState() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        android.view.MotionEvent dummyDownEvent =
                (android.view.MotionEvent) unsafeClass
                        .getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, android.view.MotionEvent.class);

        java.lang.reflect.Field activeDownField = PanelMotion.class.getDeclaredField("activeRemapDownTime");
        java.lang.reflect.Field activeDeltaField = PanelMotion.class.getDeclaredField("activeRemapDeltaX");
        java.lang.reflect.Field activeWidthField = PanelMotion.class.getDeclaredField("activeRemapWidth");
        java.lang.reflect.Field finishedDownField = PanelMotion.class.getDeclaredField("finishedRemapDownTime");
        java.lang.reflect.Field finishedDeltaField = PanelMotion.class.getDeclaredField("finishedRemapDeltaX");
        activeDownField.setAccessible(true);
        activeDeltaField.setAccessible(true);
        activeWidthField.setAccessible(true);
        finishedDownField.setAccessible(true);
        finishedDeltaField.setAccessible(true);

        java.lang.reflect.Method handleStream = PanelMotion.class.getDeclaredMethod(
                "handlePullDownTouchStream",
                android.view.MotionEvent.class,
                android.view.View.class,
                String.class);
        handleStream.setAccessible(true);

        try {
            // 1. When NotificationShadeWindowView already armed activeRemap for downTime=0L,
            // a duplicate ACTION_DOWN (action=0, downTime=0L) reaching ControlCenterEventHandlerImpl
            // must preserve activeRemapDeltaX instead of wiping it back to 0f.
            activeDownField.setLong(null, 0L);
            activeDeltaField.setFloat(null, 181f);
            activeWidthField.setInt(null, CoverDisplay.WIDTH);
            finishedDownField.setLong(null, -1L);
            finishedDeltaField.setFloat(null, 0f);

            handleStream.invoke(null, dummyDownEvent, null, "ControlCenterEventHandlerImpl");
            assertEquals(0L, activeDownField.getLong(null));
            assertEquals(181f, activeDeltaField.getFloat(null), 0.01f);

            // 2. A fresh ACTION_DOWN with a new downTime clears stale active & finished remap state
            activeDownField.setLong(null, 999L);
            activeDeltaField.setFloat(null, 181f);
            finishedDownField.setLong(null, 888L);
            finishedDeltaField.setFloat(null, 181f);

            Object passthrough = handleStream.invoke(null, dummyDownEvent, null, "NotificationShadeWindowView");
            assertEquals(dummyDownEvent, passthrough);
            assertEquals(-1L, activeDownField.getLong(null));
            assertEquals(0f, activeDeltaField.getFloat(null), 0.01f);
            assertEquals(-1L, finishedDownField.getLong(null));
            assertEquals(0f, finishedDeltaField.getFloat(null), 0.01f);
        } finally {
            activeDownField.setLong(null, -1L);
            activeDeltaField.setFloat(null, 0f);
            finishedDownField.setLong(null, -1L);
            finishedDeltaField.setFloat(null, 0f);
        }
    }

    @Test
    public void weTypeVersionSupport_andObfuscatedMethodTableFor400And401() {
        // Below 4.0.0 (58000) and >= 60000 are outside verified m1 table range
        assertFalse(AppCompatibility.isSupportedWeTypeM1Version(57999L));
        assertFalse(AppCompatibility.isWeTypeV401(57999L));
        assertFalse(AppCompatibility.isSupportedWeTypeM1Version(60000L));
        assertFalse(AppCompatibility.isWeTypeV401(60000L));

        // WeType 4.0.0 (58xxx)
        assertTrue(AppCompatibility.isSupportedWeTypeM1Version(58000L));
        assertTrue(AppCompatibility.isSupportedWeTypeM1Version(58999L));
        assertFalse(AppCompatibility.isWeTypeV401(58000L));
        assertFalse(AppCompatibility.isWeTypeV401(58999L));
        assertEquals("h3", AppCompatibility.weTypeUpdateResourceLayoutMethod(58500L));
        assertEquals("j3", AppCompatibility.weTypeModeFlowMethod(58500L));
        assertEquals("e2", AppCompatibility.weTypeRebuildAllMethod(58500L));
        assertEquals("i2", AppCompatibility.weTypeResetDataCacheMethod(58500L));
        assertEquals("j0", AppCompatibility.weTypeDefaultBodyHeightMethod(58500L));
        assertEquals("F2", AppCompatibility.weTypeSelfDrawScaleMethod(58500L));

        // WeType 4.0.1 (59201, 59xxx)
        assertTrue(AppCompatibility.isSupportedWeTypeM1Version(59000L));
        assertTrue(AppCompatibility.isSupportedWeTypeM1Version(59201L));
        assertTrue(AppCompatibility.isSupportedWeTypeM1Version(59999L));
        assertTrue(AppCompatibility.isWeTypeV401(59000L));
        assertTrue(AppCompatibility.isWeTypeV401(59201L));
        assertTrue(AppCompatibility.isWeTypeV401(59999L));
        assertEquals("f3", AppCompatibility.weTypeUpdateResourceLayoutMethod(59201L));
        assertEquals("h3", AppCompatibility.weTypeModeFlowMethod(59201L));
        assertEquals("c2", AppCompatibility.weTypeRebuildAllMethod(59201L));
        assertEquals("g2", AppCompatibility.weTypeResetDataCacheMethod(59201L));
        assertEquals("k0", AppCompatibility.weTypeDefaultBodyHeightMethod(59201L));
        assertEquals("B2", AppCompatibility.weTypeSelfDrawScaleMethod(59201L));

        android.view.ViewGroup fakeContainer = new android.widget.FrameLayout(null);
        android.view.View fakeRoot = new android.view.View(null);
        assertTrue(fakeRoot == AppCompatibility.weTypeResetInputViewArg(58100L, fakeContainer, fakeRoot));
        assertTrue(fakeContainer == AppCompatibility.weTypeResetInputViewArg(59201L, fakeContainer, fakeRoot));
    }

    @Test
    public void weTypeRotationResolution_rot0OverridesStaleRot180AndRestoresInnerScreen() {
        // Flipping from inverted cover (ROTATION_180 = 2, 1208px) back to upright cover (ROTATION_0 = 0, 810px):
        // live ROTATION_0 (0) must NOT be ignored by a > 0 check and must override stale currentImeRotation = 2
        int rotBackToUpright = AppCompatibility.resolveImeRotationForConfig(
                null, Surface.ROTATION_0, Surface.ROTATION_180);
        assertEquals(Surface.ROTATION_0, rotBackToUpright);
        assertEquals(810, CoverDisplay.coverImeWidth(rotBackToUpright));
        assertEquals(0, CoverDisplay.coverImeCandidateLeftInset(rotBackToUpright));
        assertTrue(CoverDisplay.isCoverImeWidth(810));
        assertTrue(CoverDisplay.isCoverImeWidth(1208));
        assertTrue(CoverDisplay.isCoverImeWidth(664));
        assertTrue(CoverDisplay.isCoverImeWidth(1392));
        assertFalse(CoverDisplay.isCoverImeWidth(1224));
        assertFalse(CoverDisplay.isCoverImeWidth(-1));

        // Inverting cover from ROTATION_0 (0) to ROTATION_180 (2): camera avoidance active (1208px wide, 398px left inset)
        int rotToInverted = AppCompatibility.resolveImeRotationForConfig(
                null, Surface.ROTATION_180, Surface.ROTATION_0);
        assertEquals(Surface.ROTATION_180, rotToInverted);
        assertEquals(1208, CoverDisplay.coverImeWidth(rotToInverted));
        assertEquals(398, CoverDisplay.coverImeCandidateLeftInset(rotToInverted));
        assertEquals(730, CoverDisplay.coverImeMaxHeight(rotToInverted));

        // Unavailable live rotation (-1) preserves valid current rotation including ROTATION_0 (0)
        assertEquals(Surface.ROTATION_0,
                AppCompatibility.resolveImeRotationForConfig(null, -1, Surface.ROTATION_0));
        assertEquals(Surface.ROTATION_180,
                AppCompatibility.resolveImeRotationForConfig(null, -1, Surface.ROTATION_180));
        assertEquals(Surface.ROTATION_0,
                AppCompatibility.resolveImeRotationForConfig(null, -1, -1));

        // Full Metrics + Configuration transition: Inner (1224x2912) -> ROTATION_180 (1208px) -> ROTATION_0 (810px) -> Inner
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        dm.widthPixels = 1224;
        dm.heightPixels = 2912;
        dm.densityDpi = 460;
        dm.density = 460 / 160f;
        dm.scaledDensity = dm.density;

        android.content.res.Configuration cfg = new android.content.res.Configuration();
        cfg.densityDpi = 460;
        cfg.screenWidthDp = 425;
        cfg.screenHeightDp = 1012;
        cfg.smallestScreenWidthDp = 425;
        cfg.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;

        // Step 1: Cover inverted (ROTATION_180)
        AppCompatibility.overrideMetrics(dm,
                CoverDisplay.coverImeWidth(Surface.ROTATION_180),
                CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_180),
                CoverDisplay.coverImeDensityDpi(Surface.ROTATION_180));
        AppCompatibility.adaptImeConfig(cfg, Surface.ROTATION_180);
        assertEquals(1208, dm.widthPixels);
        assertEquals(420, dm.densityDpi);
        assertEquals(420, cfg.densityDpi);
        assertEquals(372, cfg.screenWidthDp);
        assertEquals(530, cfg.screenHeightDp);

        // Step 2: Cover flipped upright back to ROTATION_0 (must shrink from 1208px to 810px safe column)
        AppCompatibility.overrideMetrics(dm,
                CoverDisplay.coverImeWidth(Surface.ROTATION_0),
                CoverDisplay.coverImeDisplayHeight(Surface.ROTATION_0),
                CoverDisplay.coverImeDensityDpi(Surface.ROTATION_0));
        AppCompatibility.adaptImeConfig(cfg, Surface.ROTATION_0);
        assertEquals(810, dm.widthPixels);
        assertEquals(340, dm.densityDpi);
        assertEquals(340, cfg.densityDpi);
        assertEquals(372, cfg.screenWidthDp);
        assertEquals(655, cfg.screenHeightDp);

        // Step 3: Unfold back to inner screen (must restore full 1224x2912 @ 460dpi without stale 810/1208 width)
        AppCompatibility.restoreOverrides(false);
        assertEquals(1224, dm.widthPixels);
        assertEquals(2912, dm.heightPixels);
        assertEquals(460, dm.densityDpi);
        assertEquals(460, cfg.densityDpi);
        assertEquals(425, cfg.screenWidthDp);
        assertEquals(1012, cfg.screenHeightDp);
    }

    @Test
    public void invertedCoverPullDown_middleThirdOpensNotificationsAndRightThirdOpensControlCenter() {
        final int w = CoverDisplay.WIDTH; // 1208

        // Upright cover (ROTATION_0): left 1/3 (< 402.67) notifications, right 2/3 (>= 402.67) Control Center
        assertEquals(w / 3f, CoverDisplay.controlCenterSplitX(w, Surface.ROTATION_0), 0.01f);
        assertFalse(CoverDisplay.isControlCenterPull(200f, w, Surface.ROTATION_0));
        assertFalse(CoverDisplay.isControlCenterPull(402f, w, Surface.ROTATION_0));
        assertTrue(CoverDisplay.isControlCenterPull(403f, w, Surface.ROTATION_0));
        assertTrue(CoverDisplay.isControlCenterPull(604f, w, Surface.ROTATION_0));
        assertEquals(298f, PanelMotion.computePullDownDeltaX(403f, w, Surface.ROTATION_0), 0.01f);

        // Inverted cover (ROTATION_180, camera occupies top-left [0..398]):
        // middle 1/3 [403..805] (and any left touch < 805.33) -> Notification Center, clamped into [459..749]
        // rightmost 1/3 (>= 805.33) -> Control Center, shifted to >= 906
        assertEquals((w * 2f) / 3f, CoverDisplay.controlCenterSplitX(w, Surface.ROTATION_180), 0.01f);
        assertFalse(CoverDisplay.isControlCenterPull(410f, w, Surface.ROTATION_180));
        assertFalse(CoverDisplay.isControlCenterPull(604f, w, Surface.ROTATION_180));
        assertFalse(CoverDisplay.isControlCenterPull(805f, w, Surface.ROTATION_180));
        assertTrue(CoverDisplay.isControlCenterPull(806f, w, Surface.ROTATION_180));
        assertTrue(CoverDisplay.isControlCenterPull(1100f, w, Surface.ROTATION_180));

        // Near camera edge (x = 410) shifts right to 459 (safe inside MiuiSystemUI [398..803] notification zone)
        assertEquals(49f, PanelMotion.computePullDownDeltaX(410f, w, Surface.ROTATION_180), 0.01f);
        // Center of middle 1/3 (x = 604) needs no horizontal shift
        assertEquals(0f, PanelMotion.computePullDownDeltaX(604f, w, Surface.ROTATION_180), 0.01f);
        // Near right edge of middle 1/3 (x = 800) shifts left to 749 (safely < 803 Control Center boundary)
        assertEquals(-51f, PanelMotion.computePullDownDeltaX(800f, w, Surface.ROTATION_180), 0.01f);
        // Rightmost 1/3 boundary (x = 806) shifts right to 906 (safely > 803 Control Center boundary)
        assertEquals(100f, PanelMotion.computePullDownDeltaX(806f, w, Surface.ROTATION_180), 0.01f);
        // Far right (x = 1050) needs no shift
        assertEquals(0f, PanelMotion.computePullDownDeltaX(1050f, w, Surface.ROTATION_180), 0.01f);
    }

    @Test
    public void voiceAssistCoverHalo_normalizesUprightAndInvertedLayoutParamsAndRestoresInner() {
        assertTrue(AppCompatibility.isVoiceAssistHaloWindowTitle("voice_assist_root"));
        assertTrue(AppCompatibility.isVoiceAssistHaloWindowTitle("voice_assist_gesture_line_handoff_effect"));
        assertFalse(AppCompatibility.isVoiceAssistHaloWindowTitle("com.miui.voiceassist/com.xiaomi.voiceassistant.tabhome.TabHomeActivity"));
        assertFalse(AppCompatibility.isVoiceAssistHaloWindowTitle(""));
        assertFalse(AppCompatibility.isVoiceAssistHaloWindowTitle(null));

        // Rotation-aware physical cover corner radii [TL, TR, BR, BL]:
        // Upright ROTATION_0 has 50px hinge at top, 130px outer bezel at bottom;
        // Inverted ROTATION_180 has 130px outer bezel at top, 50px hinge at bottom.
        assertArrayEquals(new int[]{50, 50, 130, 130}, CoverDisplay.outerCornerRadii(Surface.ROTATION_0));
        assertArrayEquals(new int[]{50, 130, 130, 50}, CoverDisplay.outerCornerRadii(Surface.ROTATION_90));
        assertArrayEquals(new int[]{130, 130, 50, 50}, CoverDisplay.outerCornerRadii(Surface.ROTATION_180));
        assertArrayEquals(new int[]{130, 50, 50, 130}, CoverDisplay.outerCornerRadii(Surface.ROTATION_270));
        assertEquals(0, CoverDisplay.outerCornerRadius(Surface.ROTATION_0, -1));
        assertEquals(0, CoverDisplay.outerCornerRadius(Surface.ROTATION_0, 4));

        assertTrue(AppCompatibility.isVoiceAssistFullscreenHaloWindow(
                2017, "voice_assist_root", "android.widget.FrameLayout"));
        assertTrue(AppCompatibility.isVoiceAssistFullscreenHaloWindow(
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                "",
                "com.xiaomi.voiceassistant.mainui.board.FloatViewRootLayout"));
        // Activity windows (< FIRST_SYSTEM_WINDOW) must never be treated as fullscreen halo overlays
        assertFalse(AppCompatibility.isVoiceAssistFullscreenHaloWindow(
                android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION,
                "voice_assist_root",
                "com.xiaomi.voiceassistant.mainui.board.FloatViewRootLayout"));

        // Simulate stale inner-screen / ROTATION_0 LayoutParams (1224x2912, Gravity.TOP, SHORT_EDGES)
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
        lp.type = 2017;
        lp.width = 1224;
        lp.height = 2912;
        lp.x = -199;
        lp.y = 0;
        lp.gravity = android.view.Gravity.TOP;
        lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        // Upright cover (ROTATION_0): snaps to 1208x1392, TOP|START, CUTOUT_MODE_ALWAYS
        assertTrue(AppCompatibility.normalizeVoiceAssistCoverHaloLayoutParams(lp, Surface.ROTATION_0));
        assertEquals(CoverDisplay.WIDTH, lp.width);
        assertEquals(CoverDisplay.HEIGHT, lp.height);
        assertEquals(0, lp.x);
        assertEquals(0, lp.y);
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.START, lp.gravity);
        assertEquals(
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                lp.layoutInDisplayCutoutMode);
        assertFalse(AppCompatibility.normalizeVoiceAssistCoverHaloLayoutParams(lp, Surface.ROTATION_0));

        // Inverted cover (ROTATION_180): keeps 1208x1392, TOP|START, CUTOUT_MODE_ALWAYS
        assertFalse(AppCompatibility.normalizeVoiceAssistCoverHaloLayoutParams(lp, Surface.ROTATION_180));
        assertEquals(CoverDisplay.WIDTH, lp.width);
        assertEquals(CoverDisplay.HEIGHT, lp.height);

        // Landscape cover (ROTATION_90): swaps to 1392x1208
        assertTrue(AppCompatibility.normalizeVoiceAssistCoverHaloLayoutParams(lp, Surface.ROTATION_90));
        assertEquals(CoverDisplay.HEIGHT, lp.width);
        assertEquals(CoverDisplay.WIDTH, lp.height);

        // Unfolding back to inner screen (1224x2912 at ROTATION_0): restores 1224x2912 stock params
        assertTrue(AppCompatibility.restoreVoiceAssistInnerHaloLayoutParams(lp, 1224, 2912, Surface.ROTATION_0));
        assertEquals(1224, lp.width);
        assertEquals(2912, lp.height);
        assertEquals(android.view.Gravity.TOP, lp.gravity);
        assertEquals(
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                lp.layoutInDisplayCutoutMode);
        // Once restored to inner dimensions, repeated calls are no-ops
        assertFalse(AppCompatibility.restoreVoiceAssistInnerHaloLayoutParams(lp, 1224, 2912, Surface.ROTATION_0));
    }

    @Test
    public void weTypeInnerUnfoldSanitization_andSelfDrawKeyWidthStalenessDetection() {
        // 1. During HyperOS's ~50ms unfold transition before ActivityThread.updateSizeCompatBounds(null),
        // Display.getRealMetrics() can return cover-clamped 810x1392 @ 340dpi (or 0.8x 1011x1740 @ 416dpi)
        // even though Display.Mode already reports physical 1224x2912.
        android.util.DisplayMetrics clampedRot0 = new android.util.DisplayMetrics();
        clampedRot0.widthPixels = 810;
        clampedRot0.heightPixels = 1392;
        clampedRot0.densityDpi = 340;
        clampedRot0.density = 340 / 160f;
        clampedRot0.scaledDensity = clampedRot0.density * 1.1f;

        assertTrue(AppCompatibility.sanitizeInnerRealMetrics(
                clampedRot0, 1224, 2912, Surface.ROTATION_0, 520));
        assertEquals(1224, clampedRot0.widthPixels);
        assertEquals(2912, clampedRot0.heightPixels);
        assertEquals(520, clampedRot0.densityDpi);
        assertEquals(520 / 160f, clampedRot0.density, 0.001f);
        assertEquals((520 / 160f) * 1.1f, clampedRot0.scaledDensity, 0.001f);

        // Landscape inner screen (ROTATION_90) synthesizes 2912x1224
        android.util.DisplayMetrics clampedCompat = new android.util.DisplayMetrics();
        clampedCompat.widthPixels = 1011;
        clampedCompat.heightPixels = 1740;
        clampedCompat.densityDpi = 416;
        clampedCompat.density = 416 / 160f;
        clampedCompat.scaledDensity = clampedCompat.density;

        assertTrue(AppCompatibility.sanitizeInnerRealMetrics(
                clampedCompat, 1224, 2912, Surface.ROTATION_90, 520));
        assertEquals(2912, clampedCompat.widthPixels);
        assertEquals(1224, clampedCompat.heightPixels);
        assertEquals(520, clampedCompat.densityDpi);

        // Valid inner metrics (including custom user DPI like 460) must be left untouched
        android.util.DisplayMetrics healthyInner = new android.util.DisplayMetrics();
        healthyInner.widthPixels = 1224;
        healthyInner.heightPixels = 2912;
        healthyInner.densityDpi = 460;
        healthyInner.density = 460 / 160f;
        healthyInner.scaledDensity = healthyInner.density;
        assertFalse(AppCompatibility.sanitizeInnerRealMetrics(
                healthyInner, 1224, 2912, Surface.ROTATION_0, 520));
        assertEquals(460, healthyInner.densityDpi);

        // 2. Full-row self-draw keyboard class filter (includes Qwerty/T9/Number/Symbol, excludes S11EmojiKeyboard & HandWrite)
        assertTrue(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S2ChineseQwertyKeyboard"));
        assertTrue(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S1ChineseT9Keyboard"));
        assertTrue(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S3EnglishQwertyKeyboard"));
        assertTrue(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S4NumberKeyboard"));
        assertFalse(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard"));
        assertFalse(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.HandWriteFullScreenKeyboard"));
        assertFalse(AppCompatibility.isFullRowWeTypeSelfDrawKeyboardClass(
                "com.tencent.wetype.plugin.hld.symbol.ImeSymbolKeyboardView"));

        // 3. Laid-out key row width (selfdraw.o.N) vs expected keyboard width (m1.I0):
        // Stale 810px keys inside 1224px inner keyboard -> stale
        assertTrue(AppCompatibility.isWeTypeLaidOutKeyWidthStale(806, 1224));
        assertTrue(AppCompatibility.isWeTypeLaidOutKeyWidthStale(810, 1208));
        assertTrue(AppCompatibility.isWeTypeLaidOutKeyWidthStale(1204, 810));
        // Healthy inner full-width (rounding diff <= 24px) -> not stale
        assertFalse(AppCompatibility.isWeTypeLaidOutKeyWidthStale(1208, 1224));
        assertFalse(AppCompatibility.isWeTypeLaidOutKeyWidthStale(1224, 1224));
        // Healthy one-handed mode on inner screen (both m1.I0 and o.N shrink together) -> not stale
        assertFalse(AppCompatibility.isWeTypeLaidOutKeyWidthStale(952, 960));
        // Healthy cover ROTATION_0 and ROTATION_180 -> not stale
        assertFalse(AppCompatibility.isWeTypeLaidOutKeyWidthStale(802, 810));
        assertFalse(AppCompatibility.isWeTypeLaidOutKeyWidthStale(1196, 1208));
    }
}
