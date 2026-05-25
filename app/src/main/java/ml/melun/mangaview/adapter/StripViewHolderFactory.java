package ml.melun.mangaview.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ml.melun.mangaview.R;

final class StripViewHolderFactory {
    private final LayoutInflater inflater;
    private final StripImageViewHolder.ClickListener clickListener;
    private final StripImageViewHolder.RefreshListener refreshListener;

    StripViewHolderFactory(LayoutInflater inflater, StripImageViewHolder.ClickListener clickListener,
                           StripImageViewHolder.RefreshListener refreshListener) {
        this.inflater = inflater;
        this.clickListener = clickListener;
        this.refreshListener = refreshListener;
    }

    @NonNull
    RecyclerView.ViewHolder create(@NonNull ViewGroup parent, int viewType) {
        if(viewType == StripAdapter.IMG) {
            View view = inflater.inflate(R.layout.item_strip, parent, false);
            return new StripImageViewHolder(view, clickListener, refreshListener);
        }
        View view = inflater.inflate(R.layout.item_strip_info, parent, false);
        return new StripInfoViewHolder(view, clickListener);
    }
}
