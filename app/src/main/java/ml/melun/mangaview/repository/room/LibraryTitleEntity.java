package ml.melun.mangaview.repository.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "library_titles", primaryKeys = {"scope", "baseMode", "titleId"})
public class LibraryTitleEntity {
    @NonNull
    public String scope = "";
    public int baseMode;
    public int titleId;
    public int sortOrder;
    public long updatedAt;
    public String payloadJson;
}
