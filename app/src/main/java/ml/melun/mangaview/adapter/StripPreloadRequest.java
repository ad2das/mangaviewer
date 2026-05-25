package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;

import java.io.File;

final class StripPreloadRequest {
    interface FailureCallback {
        void onFailed();
    }

    private StripPreloadRequest() {
    }

    static void sourceOnly(Context context,
                           Object model,
                           Priority priority,
                           FailureCallback failureCallback) {
        Glide.with(context)
                .downloadOnly()
                .priority(priority)
                .load(model)
                .listener(new RequestListener<File>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<File> target, boolean isFirstResource) {
                        failureCallback.onFailed();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(File resource, Object model, Target<File> target, DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .preload();
    }

    static void decoded(Context context,
                        Object model,
                        Priority priority,
                        RequestOptions options,
                        CustomTarget<Bitmap> target) {
        Glide.with(context)
                .asBitmap()
                .priority(priority)
                .apply(options)
                .load(model)
                .into(target);
    }
}
