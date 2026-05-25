package ml.melun.mangaview.adapter;

import android.content.Context;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import ml.melun.mangaview.glide.ViewerPageTransformation;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.model.PageItem;

import static ml.melun.mangaview.Utils.getGlideUrl;

final class StripImageRequestPolicy {
    private StripImageRequestPolicy() {
    }

    static RequestOptions imageOptions(Context context, PageItem item, boolean scrollBusy,
                                       boolean autoCut, boolean reverse, int width) {
        int targetWidth = Math.max(width, 1);
        RequestOptions options = baseOptions(context, scrollBusy).override(targetWidth, Target.SIZE_ORIGINAL);
        if(item != null)
            options = options.transform(new ViewerPageTransformation(item, autoCut, reverse, targetWidth));
        return options;
    }

    static RequestOptions previewOptions(Context context, PageItem item, boolean scrollBusy,
                                         boolean autoCut, boolean reverse, int viewerWidth) {
        int previewWidth = previewWidth(viewerWidth);
        RequestOptions options = baseOptions(context, scrollBusy).override(previewWidth, Target.SIZE_ORIGINAL);
        if(item != null)
            options = options.transform(new ViewerPageTransformation(item, autoCut, reverse, previewWidth));
        return options;
    }

    static int previewWidth(int viewerWidth) {
        return StripImagePolicy.previewWidth(viewerWidth);
    }

    static DiskCacheStrategy viewerDiskCacheStrategy(boolean scrollBusy) {
        return DiskCacheStrategy.DATA;
    }

    static Object imageModel(PageItem item) {
        if(item == null)
            return "";
        if(!StripPageKeyPolicy.isUsableImageUrl(item.img))
            return "";
        if(item.manga == null)
            return item.img;
        return item.manga.isOnline() ? getGlideUrl(item.img, item.manga.getBaseMode()) : item.img;
    }

    private static RequestOptions baseOptions(Context context, boolean scrollBusy) {
        return new RequestOptions()
                .diskCacheStrategy(ViewerWarmupManager.viewerDiskCacheStrategy(context, scrollBusy))
                .format(DecodeFormat.PREFER_RGB_565)
                .downsample(DownsampleStrategy.AT_MOST)
                .dontAnimate();
    }
}
