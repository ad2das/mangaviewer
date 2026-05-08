package ml.melun.mangaview.fragment;

import static ml.melun.mangaview.Utils.getGlideUrl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.fragment.app.Fragment;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import ml.melun.mangaview.R;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.PageInterface;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.model.PageItem;

import static ml.melun.mangaview.MainApplication.p;

public class ViewerPageFragment extends Fragment {
    String image;
    Decoder decoder;
    Context context;
    PageInterface i;
    int width;
    PageItem pageItem;

    public ViewerPageFragment(){

    }
    public ViewerPageFragment(String image, Decoder decoder, int width, Context context, PageInterface i){
        this.image = image;
        this.decoder = decoder;
        this.width = width;
        this.context = context;
        this.i = i;
    }
    public ViewerPageFragment(String image, Decoder decoder, int width, Context context, PageInterface i, PageItem pageItem){
        this(image, decoder, width, context, i);
        this.pageItem = pageItem;
    }
    public static Fragment create(String image, Decoder decoder, int width, Context context, PageInterface i){
        return new ViewerPageFragment(image, decoder, width, context, i);
    }
    public static Fragment create(String image, Decoder decoder, int width, Context context, PageInterface i, PageItem pageItem){
        return new ViewerPageFragment(image, decoder, width, context, i, pageItem);
    }

    public void updatePageFragment(Context context){
        this.context = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_viewer, container, false);
        ImageView frame = rootView.findViewById(R.id.page);
        ImageButton refresh = rootView.findViewById(R.id.refreshButton);
        //glide
        frame.setImageResource(R.drawable.placeholder);
        refresh.setVisibility(View.VISIBLE);

        if(context != null)
            loadImage(frame, refresh);

        refresh.setOnClickListener(v -> {
            if(context != null) {
                loadImage(frame, refresh);
            }
        });
        rootView.setOnClickListener(v -> i.onPageClick());

        return rootView;
    }

    void loadImage(ImageView frame, ImageButton refresh){
        Bitmap cached = pageItem == null ? null : ViewerWarmupManager.getDecodedBitmap(pageItem, false, p.getReverse(), width);
        if(cached != null && !cached.isRecycled()) {
            refresh.setVisibility(View.GONE);
            frame.setImageBitmap(cached);
            if(pageItem.index > 0)
                ViewerWarmupManager.logMetric("viewer_next_page_cache_hit", 1);
            return;
        }
        long bindStart = android.os.SystemClock.elapsedRealtime();
        Object target = image.startsWith("http") ? getGlideUrl(image) : image;
        Glide.with(frame)
                .asBitmap()
                .priority(Priority.IMMEDIATE)
                .apply(viewerImageOptions())
                .load(target)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                        refresh.setVisibility(View.GONE);
                        bitmap = decoder.decode(bitmap,width);
                        frame.setImageBitmap(bitmap);
                        if(pageItem != null && pageItem.index == 0)
                            ViewerWarmupManager.logMetric("viewer_first_bind_ms", android.os.SystemClock.elapsedRealtime() - bindStart);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        //
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        if(image.length()>0) {
                            frame.setImageResource(R.drawable.placeholder);
                            refresh.setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    private RequestOptions viewerImageOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(width, 1), Target.SIZE_ORIGINAL);
    }

    public void setOnClick(PageInterface i){
        this.i = i;
    }
}
