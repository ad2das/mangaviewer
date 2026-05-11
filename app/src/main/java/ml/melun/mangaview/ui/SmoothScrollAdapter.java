package ml.melun.mangaview.ui;

import androidx.recyclerview.widget.RecyclerView;

public interface SmoothScrollAdapter {
    void setScrollBusy(boolean busy);

    void onScrollIdle(RecyclerView recyclerView);
}
