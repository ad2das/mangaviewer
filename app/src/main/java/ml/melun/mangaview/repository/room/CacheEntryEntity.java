package ml.melun.mangaview.repository.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "cache_entries")
public class CacheEntryEntity {
    @PrimaryKey
    @NonNull
    public String cacheKey = "";
    public String payloadJson;
    public long loadedAt;
    public long ttlMs;
}
