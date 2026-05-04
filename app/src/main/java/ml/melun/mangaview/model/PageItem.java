package ml.melun.mangaview.model;

import androidx.annotation.Nullable;

import java.util.Objects;

import ml.melun.mangaview.mangaview.Manga;

public class PageItem{
    public static final int FIRST = 0;
    public static final int SECOND = 1;
    public PageItem(int index, String img, Manga manga) {
        this.index = index;
        this.img = img;
        this.manga = manga;
        this.side = FIRST;
    }
    public PageItem(int index, String img, Manga manga, int side){
        this.index = index;
        this.img = img;
        this.manga = manga;
        this.side = side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                manga == null ? 0 : manga.getBaseMode(),
                manga == null ? 0 : manga.getId(),
                index,
                side,
                img
        );
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if(obj instanceof PageItem){
            PageItem p = (PageItem)obj;
            return p.index == this.index
                    && p.side == this.side
                    && Objects.equals(p.img, this.img)
                    && sameManga(p.manga, this.manga);
        }else
            return false;
    }

    private boolean sameManga(Manga a, Manga b) {
        if(a == b)
            return true;
        if(a == null || b == null)
            return false;
        return a.getBaseMode() == b.getBaseMode() && a.getId() == b.getId();
    }

    public int index;
    public int side;
    public String img;
    public Manga manga;
}
