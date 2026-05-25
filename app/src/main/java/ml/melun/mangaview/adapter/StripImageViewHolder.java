package ml.melun.mangaview.adapter;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.target.CustomTarget;

import ml.melun.mangaview.R;
import ml.melun.mangaview.ui.ViewerClippedImageView;

class StripImageViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener,
        View.OnLongClickListener {
    interface ClickListener {
        void onItemClick();
    }

    interface RefreshListener {
        void onRefreshRequested(int position);
    }

    final ViewerClippedImageView frame;
    final ImageButton refresh;
    CustomTarget<Bitmap> imageTarget;
    String boundPageKey;
    int bindGeneration = 0;
    long bindStartedAtMs = 0L;
    int appliedItemHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final ClickListener clickListener;
    private final RefreshListener refreshListener;

    StripImageViewHolder(View itemView, ClickListener clickListener, RefreshListener refreshListener) {
        super(itemView);
        this.clickListener = clickListener;
        this.refreshListener = refreshListener;
        frame = itemView.findViewById(R.id.frame);
        refresh = itemView.findViewById(R.id.refreshButton);
        refresh.setOnClickListener(v -> {
            int position = getAdapterPosition();
            if(position != RecyclerView.NO_POSITION && this.refreshListener != null)
                this.refreshListener.onRefreshRequested(position);
        });
        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(this);
    }

    @Override
    public void onClick(View view) {
        if(clickListener != null)
            clickListener.onItemClick();
    }

    @Override
    public boolean onLongClick(View v) {
        return false;
    }
}
