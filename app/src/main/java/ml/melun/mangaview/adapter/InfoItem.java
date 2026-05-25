package ml.melun.mangaview.adapter;

import ml.melun.mangaview.mangaview.Manga;

class InfoItem {
    public Manga next;
    public Manga prev;

    InfoItem(Manga prev, Manga next) {
        if(next == null && prev != null)
            this.next = prev.nextEp();
        else
            this.next = next;
        if(prev == null && next != null)
            this.prev = next.prevEp();
        else
            this.prev = prev;
    }
}
