package ml.melun.mangaview.adapter;

import android.view.View;

import ml.melun.mangaview.mangaview.Manga;

final class StripInfoRowBinder {
    private StripInfoRowBinder() {
    }

    static void bind(StripInfoViewHolder holder, InfoItem info) {
        holder.loading.setVisibility(View.INVISIBLE);
        Manga prev = info.prev;
        Manga next = info.next;

        if(prev == null && next != null) {
            prev = next.prevEp();
        } else if(next == null && prev != null) {
            next = prev.nextEp();
        }

        holder.prevInfo.setText(prev == null ? "첫 화" : prev.getName());
        holder.nextInfo.setText(next == null ? "마지막 화" : next.getName());
    }
}
