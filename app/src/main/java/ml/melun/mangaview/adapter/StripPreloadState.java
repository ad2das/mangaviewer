package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;

import com.bumptech.glide.request.target.CustomTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StripPreloadState {
    private final int trackedLimit;
    private final Set<String> trackedRequests = new LinkedHashSet<>();
    private final Map<String, CustomTarget<Bitmap>> decodedTargets = new HashMap<>();

    StripPreloadState(int trackedLimit) {
        this.trackedLimit = trackedLimit;
    }

    boolean track(String key) {
        if(key == null || key.length() == 0)
            return false;
        if(!trackedRequests.add(key))
            return false;
        trimTrackedRequests();
        return true;
    }

    void untrack(String key) {
        trackedRequests.remove(key);
    }

    void clearTrackedRequests() {
        trackedRequests.clear();
    }

    void retainTrackedRequests(Set<String> activePreloadKeys) {
        trackedRequests.retainAll(activePreloadKeys);
    }

    int decodedTargetCount() {
        return decodedTargets.size();
    }

    void putDecodedTarget(String requestKey, CustomTarget<Bitmap> target) {
        if(requestKey != null && target != null)
            decodedTargets.put(requestKey, target);
    }

    CustomTarget<Bitmap> removeDecodedTarget(String requestKey) {
        return decodedTargets.remove(requestKey);
    }

    List<CustomTarget<Bitmap>> drainDecodedTargets() {
        List<CustomTarget<Bitmap>> targets = new ArrayList<>(decodedTargets.values());
        decodedTargets.clear();
        return targets;
    }

    List<CustomTarget<Bitmap>> removeDecodedTargetsNotIn(Set<String> activePreloadKeys) {
        List<CustomTarget<Bitmap>> staleTargets = new ArrayList<>();
        Iterator<Map.Entry<String, CustomTarget<Bitmap>>> iterator = decodedTargets.entrySet().iterator();
        while(iterator.hasNext()) {
            Map.Entry<String, CustomTarget<Bitmap>> entry = iterator.next();
            if(activePreloadKeys == null || !activePreloadKeys.contains(entry.getKey())) {
                staleTargets.add(entry.getValue());
                iterator.remove();
            }
        }
        return staleTargets;
    }

    private void trimTrackedRequests() {
        while(trackedRequests.size() > trackedLimit) {
            Iterator<String> iterator = trackedRequests.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }
}
