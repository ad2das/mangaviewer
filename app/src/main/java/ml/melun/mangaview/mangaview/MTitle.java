package ml.melun.mangaview.mangaview;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class MTitle{
    String name;
    int id;
    String thumb;
    String author;
    List<String> tags;
    String release;
    String path;
    String sourceSite;
    String ntkStatusLabel;
    String resumeNtkEpisodePath;
    int bookmarkEpisodeId = -1;
    int bookmarkEpisodeIndex = -1;
    int episodeCount = 0;
    int baseMode = base_comic; // default is comic
    //public static final String[] releases = {"미분류","주간","격주","월간","격월/비정기","단편","단행본","완결"};
    public MTitle(){

    }
    public MTitle(String name, int id, String thumb, String author, List<String> tags, String release, int baseMode) {
        this.name = cleanText(name);
        this.id = id;
        this.thumb = cleanNullable(thumb);
        this.tags = tags;
        this.release = cleanNullable(release);
        this.author = cleanNullable(author);
        this.baseMode = baseMode;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSourceSite() {
        return sourceSite == null ? "" : sourceSite;
    }

    public void setSourceSite(String sourceSite) {
        this.sourceSite = normalizeSourceSite(sourceSite);
    }

    private String normalizeSourceSite(String sourceSite) {
        if(sourceSite == null)
            return "";
        String lower = sourceSite.trim().toLowerCase(Locale.ROOT);
        if(lower.length() == 0)
            return "";
        if(lower.contains("ntk") || lower.contains("sbxh") || lower.contains("toonflix"))
            return "ntk";
        if(lower.contains("wfwf") || lower.contains("wolf") || lower.contains("vcloud")
                || lower.contains("v12st") || lower.contains("ao9cloud"))
            return "wfwf";
        return "";
    }

    public String getNtkStatusLabel() {
        return ntkStatusLabel == null ? "" : ntkStatusLabel;
    }

    public void setNtkStatusLabel(String ntkStatusLabel) {
        this.ntkStatusLabel = ntkStatusLabel == null ? "" : ntkStatusLabel;
    }

    public String getResumeNtkEpisodePath() {
        return resumeNtkEpisodePath == null ? "" : resumeNtkEpisodePath;
    }

    public void setResumeNtkEpisodePath(String resumeNtkEpisodePath) {
        this.resumeNtkEpisodePath = resumeNtkEpisodePath == null ? "" : resumeNtkEpisodePath.trim();
    }

    public int getBaseMode() {
        if(baseMode == base_auto)
            baseMode = base_comic;
        return baseMode;
    }

    public String getBaseModeStr(){
        return baseModeKorStr(baseMode);
    }

    public void setBaseMode(int baseMode) {
        this.baseMode = baseMode;
    }

    public String getName() {
        return cleanNullable(name);
    }

    public int getId() {
        return id;
    }

    public String getThumb() {
        return cleanNullable(thumb);
    }

    public String getAuthor() {
        if(author == null) return "";
        return author;
    }

    public List<String> getTags(){
        if(tags==null) return new ArrayList<>();
        return tags;
    }

    public String getRelease() {
        return cleanNullable(release);
    }

    public int getBookmarkEpisodeId() {
        return bookmarkEpisodeId;
    }

    public int getBookmarkEpisodeIndex() {
        return bookmarkEpisodeIndex;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }

    public int getDisplayEpisodeCount(int fallbackEpisodeCount) {
        int count = episodeCount > 0 ? episodeCount : fallbackEpisodeCount;
        int releaseCount = getNtkReleaseEpisodeCount();
        if(baseMode == base_webtoon && count > 0)
            return count;
        if(releaseCount > 0 && (count <= 0 || count > releaseCount))
            return releaseCount;
        return count;
    }

    public int getNtkReleaseEpisodeCount() {
        if(!"ntk".equals(sourceSite) || release == null)
            return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*화").matcher(release);
        if(!matcher.find())
            return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return 0;
        }
    }

    public void setReadingProgress(int episodeId, int episodeIndex, int episodeCount) {
        this.bookmarkEpisodeId = episodeId;
        this.bookmarkEpisodeIndex = episodeIndex;
        this.episodeCount = episodeCount;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = cleanText(name);
    }

    public void setThumb(String thumb) {
        this.thumb = cleanNullable(thumb);
    }

    public void setAuthor(String author) {
        this.author = cleanNullable(author);
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public void setRelease(String release) {
        this.release = cleanNullable(release);
    }

    @Override
    public MTitle clone() {
        MTitle clone = new MTitle(name, id, thumb, author, tags, release, baseMode);
        clone.setReadingProgress(bookmarkEpisodeId, bookmarkEpisodeIndex, episodeCount);
        clone.setPath(path);
        clone.setSourceSite(sourceSite);
        clone.setNtkStatusLabel(ntkStatusLabel);
        clone.setResumeNtkEpisodePath(resumeNtkEpisodePath);
        return clone;
    }

    public static final int base_auto = 0;
    public static final int base_comic = 1;
    public static final int base_webtoon = 2;

    public static String baseModeStr(int mode){
        switch(mode){
            case base_comic:
                return "comic";
            case base_webtoon:
                return "webtoon";
            default:
                return "comic";
        }
    }
    public static String baseModeKorStr(int mode){
        switch(mode){
            case base_comic:
                return "만화";
            case base_webtoon:
                return "웹툰";
            default:
                return "만화";
        }
    }

    private static String cleanText(String value) {
        return cleanNullable(value).replace("\"", "");
    }

    private static String cleanNullable(String value) {
        return value == null ? "" : value;
    }

    @NonNull
    @Override
    public String toString() {
        return name + " . " + id + " . " +  thumb + " . " + author + " . " + baseMode;
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        if(!(obj instanceof MTitle))
            return false;
        MTitle other = (MTitle) obj;
        return other.getBaseMode() == this.getBaseMode() && other.getId() == this.id;
    }

    @Override
    public int hashCode() {
        return 31 * getBaseMode() + id;
    }
}
