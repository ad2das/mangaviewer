package ml.melun.mangaview.repository.room;

import androidx.room.Entity;

@Entity(tableName = "viewer_bookmarks", primaryKeys = {"baseMode", "mangaId"})
public class ViewerBookmarkEntity {
    public int baseMode;
    public int mangaId;
    public int pageIndex;
    public int offset;
    public long updatedAt;
}
