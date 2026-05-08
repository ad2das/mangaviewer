package ml.melun.mangaview.repository.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "offline_index")
public class OfflineIndexEntity {
    @PrimaryKey
    @NonNull
    public String path = "";
    public String titleName;
    public int titleId;
    public int baseMode;
    public String payloadJson;
    public long indexedAt;
}
