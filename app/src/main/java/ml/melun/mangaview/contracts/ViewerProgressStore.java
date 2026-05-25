package ml.melun.mangaview.contracts;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;

public interface ViewerProgressStore {
    ViewerBookmark getViewerBookmark(Manga manga);

    void saveViewerBookmark(Manga manga, ViewerBookmark bookmark);

    void saveTitleBookmark(Title title, int episodeId);

    final class ViewerBookmark {
        public final int pageIndex;
        public final int scrollOffset;
        public final int side;

        public ViewerBookmark(int pageIndex, int scrollOffset, int side) {
            this.pageIndex = pageIndex;
            this.scrollOffset = scrollOffset;
            this.side = side;
        }

        public static ViewerBookmark firstPage() {
            return new ViewerBookmark(0, 0, PageItem.FIRST);
        }
    }
}
