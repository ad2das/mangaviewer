package ml.melun.mangaview.repository.room;

import androidx.room.Entity;

@Entity(tableName = "bookmarks", primaryKeys = {"baseMode", "titleId"})
public class BookmarkEntity {
    public int baseMode;
    public int titleId;
    public int episodeId;
    public long updatedAt;
}
