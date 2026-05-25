package ml.melun.mangaview.adapter;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

final class StripAttachedHolderState {
    private final Set<StripImageViewHolder> attachedImageHolders =
            Collections.newSetFromMap(new WeakHashMap<>());

    void add(StripImageViewHolder holder) {
        if(holder != null)
            attachedImageHolders.add(holder);
    }

    void remove(StripImageViewHolder holder) {
        if(holder != null)
            attachedImageHolders.remove(holder);
    }

    void applyFastDraw(boolean fastDraw, PreviewLookup previewLookup) {
        if(attachedImageHolders.isEmpty())
            return;
        Iterator<StripImageViewHolder> iterator = attachedImageHolders.iterator();
        while(iterator.hasNext()) {
            StripImageViewHolder holder = iterator.next();
            if(holder == null || holder.frame == null) {
                iterator.remove();
                continue;
            }
            boolean previewDraw = holder.boundPageKey != null && previewLookup.isPreviewOnly(holder.boundPageKey);
            holder.frame.setFastBitmapDraw(fastDraw || previewDraw);
        }
    }

    interface PreviewLookup {
        boolean isPreviewOnly(String pageKey);
    }
}
