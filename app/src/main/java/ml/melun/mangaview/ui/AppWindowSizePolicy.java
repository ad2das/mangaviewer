package ml.melun.mangaview.ui;

/** Pure window-height classification shared by resizable app screens. */
public final class AppWindowSizePolicy {
    private static final int COMPACT_HEIGHT_DP = 520;
    private static final int ULTRA_COMPACT_HEIGHT_DP = 300;

    private AppWindowSizePolicy() {
    }

    public static boolean isCompactHeight(int heightPixels, float density) {
        return heightDp(heightPixels, density) < COMPACT_HEIGHT_DP;
    }

    public static boolean isUltraCompactHeight(int heightPixels, float density) {
        return heightDp(heightPixels, density) < ULTRA_COMPACT_HEIGHT_DP;
    }

    private static float heightDp(int heightPixels, float density) {
        if(heightPixels <= 0 || density <= 0f)
            return Float.MAX_VALUE;
        return heightPixels / density;
    }
}
