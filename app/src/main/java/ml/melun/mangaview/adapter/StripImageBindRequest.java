package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.view.View;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;

final class StripImageBindRequest {
    private StripImageBindRequest() {
    }

    static void into(View view,
                     Object model,
                     RequestOptions imageOptions,
                     RequestOptions previewOptions,
                     boolean previewOnly,
                     CustomTarget<Bitmap> target) {
        RequestBuilder<Bitmap> request = Glide.with(view)
                .asBitmap()
                .priority(previewOnly ? Priority.IMMEDIATE : Priority.HIGH)
                .apply(previewOnly ? previewOptions : imageOptions)
                .load(model);
        if(!previewOnly) {
            request = request.thumbnail(Glide.with(view)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .apply(previewOptions)
                    .load(model));
        }
        request.into(target);
    }
}
