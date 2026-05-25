package ml.melun.mangaview.adapter;

import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import ml.melun.mangaview.R;

class StripInfoViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
    final TextView prevInfo;
    final TextView nextInfo;
    final ProgressBar loading;
    private final StripImageViewHolder.ClickListener clickListener;

    StripInfoViewHolder(View itemView, StripImageViewHolder.ClickListener clickListener) {
        super(itemView);
        this.clickListener = clickListener;
        prevInfo = itemView.findViewById(R.id.prevEpInfo);
        nextInfo = itemView.findViewById(R.id.nextEpInfo);
        loading = itemView.findViewById(R.id.infoLoading);
        itemView.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        if(clickListener != null)
            clickListener.onItemClick();
    }
}
