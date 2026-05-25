package ml.melun.mangaview.adapter;

import com.bumptech.glide.Priority;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;

final class StripPreloadWindowPolicy {
    private StripPreloadWindowPolicy() {
    }

    static ViewerPreloadPolicy.Window clamp(ViewerPreloadPolicy.Window policy, int totalLimit) {
        int limit = Math.max(1, Math.min(policy.totalLimit, Math.max(1, totalLimit)));
        return new ViewerPreloadPolicy.Window(
                Math.min(policy.decodedLimit, limit),
                Math.min(policy.immediateLimit, limit),
                Math.min(policy.highLimit, limit),
                limit
        );
    }

    static ViewerPreloadPolicy.Window reverseWindow(boolean dataSave) {
        return dataSave
                ? new ViewerPreloadPolicy.Window(0, 1, 2, 2)
                : new ViewerPreloadPolicy.Window(1, 2, 4, 4);
    }

    static Priority priorityForTier(int tier) {
        if(tier == ViewerPreloadPolicy.TIER_DECODED || tier == ViewerPreloadPolicy.TIER_IMMEDIATE)
            return Priority.IMMEDIATE;
        if(tier == ViewerPreloadPolicy.TIER_HIGH)
            return Priority.HIGH;
        return Priority.NORMAL;
    }
}
