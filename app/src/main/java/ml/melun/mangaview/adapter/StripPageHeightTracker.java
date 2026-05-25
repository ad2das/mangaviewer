package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ml.melun.mangaview.model.PageItem;

final class StripPageHeightTracker {
    private final Map<String, Integer> pageHeights = new LinkedHashMap<>();
    private final Set<String> pendingCorrections = new LinkedHashSet<>();
    private long heightTotal = 0L;
    private int heightSampleCount = 0;

    void remember(String pageKey, Bitmap bitmap, int width, boolean scrollBusy) {
        if(pageKey == null || pageKey.length() == 0 || bitmap == null || bitmap.isRecycled())
            return;
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        if(bitmapWidth <= 0 || bitmapHeight <= 0)
            return;
        int targetHeight = Math.max(1, Math.round((float)Math.max(width, 1) * bitmapHeight / bitmapWidth));
        Integer previousHeight = pageHeights.put(pageKey, targetHeight);
        if(previousHeight == null) {
            heightTotal += targetHeight;
            heightSampleCount++;
        } else {
            heightTotal += targetHeight - previousHeight;
        }
        if(scrollBusy)
            pendingCorrections.add(pageKey);
    }

    Integer knownHeight(String pageKey) {
        return pageKey == null ? null : pageHeights.get(pageKey);
    }

    boolean hasKnownHeight(String pageKey) {
        return pageKey != null && pageHeights.containsKey(pageKey);
    }

    void markPending(String pageKey) {
        if(pageKey != null && pageKey.length() > 0)
            pendingCorrections.add(pageKey);
    }

    boolean hasPendingCorrections() {
        return !pendingCorrections.isEmpty();
    }

    Set<String> drainPendingCorrections() {
        Set<String> keys = new LinkedHashSet<>(pendingCorrections);
        pendingCorrections.clear();
        return keys;
    }

    void retain(Set<String> activePageKeys) {
        pageHeights.keySet().retainAll(activePageKeys);
        pendingCorrections.retainAll(activePageKeys);
        recomputeAggregate();
    }

    void clear() {
        pageHeights.clear();
        pendingCorrections.clear();
        heightTotal = 0L;
        heightSampleCount = 0;
    }

    int estimatedHeight(boolean autoCut, int side, int width) {
        return estimatedHeight(autoCut, side, width, heightTotal, heightSampleCount);
    }

    static int estimatedHeight(boolean autoCut, int side, int width, long pageHeightTotal, int pageHeightSampleCount) {
        if(autoCut && side == PageItem.SECOND)
            return 1;
        if(pageHeightSampleCount > 0)
            return Math.max(width, Math.round((float) pageHeightTotal / pageHeightSampleCount));
        return Math.max(width, Math.round(width * 1.45f));
    }

    private void recomputeAggregate() {
        heightTotal = 0L;
        heightSampleCount = 0;
        for(Integer height : pageHeights.values()) {
            if(height != null && height > 0) {
                heightTotal += height;
                heightSampleCount++;
            }
        }
    }
}
