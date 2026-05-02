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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import ml.melun.mangaview.R;
import ml.melun.mangaview.interfaces.PageInterface;
import ml.melun.mangaview.mangaview.Decoder;

public class ViewerPageFragment extends Fragment {
    String image;
    Decoder decoder;
    Context context;
    PageInterface i;
    int width;
    ImageView frame;
    ImageButton refresh;
    CustomTarget<Bitmap> imageTarget;
    String activeImage;

    public ViewerPageFragment(){

    }
    public ViewerPageFragment(String image, Decoder decoder, int width, Context context, PageInterface i){
        this.image = image;
        this.decoder = decoder;
        this.width = width;
        this.context = context;
        this.i = i;
    }
    public static Fragment create(String image, Decoder decoder, int width, Context context, PageInterface i){
        return new ViewerPageFragment(image, decoder, width, context, i);
    }

    public void updatePageFragment(Context context){
        this.context = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_viewer, container, false);
        frame = rootView.findViewById(R.id.page);
        refresh = rootView.findViewById(R.id.refreshButton);
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
        rootView.setOnClickListener(v -> {
            if(i != null)
                i.onPageClick();
        });

        return rootView;
    }

    void loadImage(ImageView frame, ImageButton refresh){
        clearImageTarget();
        if(image == null) {
            frame.setImageResource(R.drawable.placeholder);
            refresh.setVisibility(View.VISIBLE);
            return;
        }
        activeImage = image;
        Object target = image.startsWith("http") ? getGlideUrl(image) : image;
        CustomTarget<Bitmap> targetView = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                if(!isActiveTarget(this))
                    return;
                refresh.setVisibility(View.GONE);
                Bitmap glideBitmap = bitmap;
                bitmap = decoder.decode(bitmap,width);
                bitmap = retainIfGlideOwned(bitmap, glideBitmap);
                frame.setImageBitmap(bitmap);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                if(imageTarget != this)
                    return;
                frame.setImageDrawable(placeholder);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if(!isActiveTarget(this))
                    return;
                if(image.length()>0) {
                    frame.setImageResource(R.drawable.placeholder);
                    refresh.setVisibility(View.VISIBLE);
                }
            }
        };
        imageTarget = targetView;
        Glide.with(frame)
                .asBitmap()
                .apply(viewerImageOptions())
                .load(target)
                .into(targetView);
    }

    private void clearImageTarget() {
        if(frame == null || imageTarget == null)
            return;
        CustomTarget<Bitmap> target = imageTarget;
        frame.setImageResource(R.drawable.placeholder);
        if(refresh != null)
            refresh.setVisibility(View.VISIBLE);
        imageTarget = null;
        Glide.with(frame).clear(target);
    }

    private Bitmap retainIfGlideOwned(Bitmap displayBitmap, Bitmap glideBitmap) {
        if(displayBitmap == null || displayBitmap.isRecycled() || displayBitmap != glideBitmap)
            return displayBitmap;
        try {
            return displayBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (OutOfMemoryError e) {
            return displayBitmap;
        }
    }

    private boolean isActiveTarget(CustomTarget<Bitmap> target) {
        return imageTarget == target && getView() != null && image != null && image.equals(activeImage);
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

    @Override
    public void onDestroyView() {
        clearImageTarget();
        frame = null;
        refresh = null;
        activeImage = null;
        super.onDestroyView();
    }
}
