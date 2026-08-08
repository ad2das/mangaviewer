package ml.melun.mangaview.mangaview;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class MTitle{
    public static final class ResumeNtkNextEpisodeIdentitySnapshot {
        public final String path;
        public final int id;
        public final String name;
        public final String workId;
        public final String episodeId;
        public final int imageCount;

        ResumeNtkNextEpisodeIdentitySnapshot(
                String path,
                int id,
                String name,
                String workId,
                String episodeId,
                int imageCount) {
            this.path = path;
            this.id = id;
            this.name = name;
            this.workId = workId;
            this.episodeId = episodeId;
            this.imageCount = imageCount;
        }

        public boolean isComplete() {
            return path.length() > 0 && id > 0 && workId.length() > 0
                    && episodeId.length() > 0 && imageCount > 0;
        }
    }
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
    String resumeNtkImageEpisodeId;
    String resumeNtkImageWorkId;
    int resumeNtkImageCount;
    String resumeNtkNextEpisodePath;
    int resumeNtkNextEpisodeId = -1;
    String resumeNtkNextEpisodeName;
    String resumeNtkNextImageEpisodeId;
    String resumeNtkNextImageWorkId;
    int resumeNtkNextImageCount;
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
        String normalized = resumeNtkEpisodePath == null ? "" : resumeNtkEpisodePath.trim();
        if(!normalized.equals(getResumeNtkEpisodePath())) {
            setResumeNtkImageIdentity("", "", 0);
            clearResumeNtkNextEpisodeIdentity();
        }
        this.resumeNtkEpisodePath = normalized;
    }

    public String getResumeNtkImageEpisodeId() {
        return resumeNtkImageEpisodeId == null ? "" : resumeNtkImageEpisodeId.trim();
    }

    public String getResumeNtkImageWorkId() {
        return resumeNtkImageWorkId == null ? "" : resumeNtkImageWorkId.trim();
    }

    public int getResumeNtkImageCount() {
        return Math.max(0, resumeNtkImageCount);
    }

    public void setResumeNtkImageIdentity(String workId, String episodeId, int imageCount) {
        resumeNtkImageWorkId = workId == null ? "" : workId.trim();
        resumeNtkImageEpisodeId = episodeId == null ? "" : episodeId.trim();
        resumeNtkImageCount = Math.max(0, imageCount);
    }

    public void inheritMissingResumeNtkImageIdentity(MTitle source) {
        if(source == null || !getResumeNtkEpisodePath().equals(source.getResumeNtkEpisodePath()))
            return;
        String workId = getResumeNtkImageWorkId();
        String episodeId = getResumeNtkImageEpisodeId();
        int imageCount = getResumeNtkImageCount();
        if(workId.length() == 0)
            workId = source.getResumeNtkImageWorkId();
        if(episodeId.length() == 0)
            episodeId = source.getResumeNtkImageEpisodeId();
        if(imageCount <= 0)
            imageCount = source.getResumeNtkImageCount();
        setResumeNtkImageIdentity(workId, episodeId, imageCount);
    }

    public String getResumeNtkNextEpisodePath() {
        return resumeNtkNextEpisodePath == null ? "" : resumeNtkNextEpisodePath.trim();
    }

    public int getResumeNtkNextEpisodeId() {
        return resumeNtkNextEpisodeId;
    }

    public String getResumeNtkNextEpisodeName() {
        return resumeNtkNextEpisodeName == null ? "" : resumeNtkNextEpisodeName;
    }

    public String getResumeNtkNextImageEpisodeId() {
        return resumeNtkNextImageEpisodeId == null ? "" : resumeNtkNextImageEpisodeId.trim();
    }

    public String getResumeNtkNextImageWorkId() {
        return resumeNtkNextImageWorkId == null ? "" : resumeNtkNextImageWorkId.trim();
    }

    public int getResumeNtkNextImageCount() {
        return Math.max(0, resumeNtkNextImageCount);
    }

    public boolean hasCompleteResumeNtkNextEpisodeIdentity() {
        return getResumeNtkNextEpisodePath().length() > 0
                && getResumeNtkNextEpisodeId() > 0
                && getResumeNtkNextImageWorkId().length() > 0
                && getResumeNtkNextImageEpisodeId().length() > 0
                && getResumeNtkNextImageCount() > 0;
    }

    public synchronized void setResumeNtkNextEpisodeIdentity(
            String path,
            int id,
            String name,
            String workId,
            String episodeId,
            int imageCount) {
        String normalizedPath = path == null ? "" : path.trim();
        String normalizedWorkId = workId == null ? "" : workId.trim();
        String normalizedEpisodeId = episodeId == null ? "" : episodeId.trim();
        if(normalizedPath.length() == 0 || id <= 0 || normalizedWorkId.length() == 0
                || normalizedEpisodeId.length() == 0 || imageCount <= 0) {
            clearResumeNtkNextEpisodeIdentity();
            return;
        }
        resumeNtkNextEpisodePath = normalizedPath;
        resumeNtkNextEpisodeId = id;
        resumeNtkNextEpisodeName = name == null ? "" : name;
        resumeNtkNextImageWorkId = normalizedWorkId;
        resumeNtkNextImageEpisodeId = normalizedEpisodeId;
        resumeNtkNextImageCount = imageCount;
    }

    public void setResumeNtkNextEpisodeIdentity(Manga episode) {
        if(episode == null) {
            clearResumeNtkNextEpisodeIdentity();
            return;
        }
        setResumeNtkNextEpisodeIdentity(
                episode.getNtkEpisodePath(),
                episode.getId(),
                episode.getName(),
                episode.getNtkImageWorkId(),
                episode.getNtkImageEpisodeId(),
                episode.getNtkImageCount());
    }

    public synchronized void clearResumeNtkNextEpisodeIdentity() {
        resumeNtkNextEpisodePath = "";
        resumeNtkNextEpisodeId = -1;
        resumeNtkNextEpisodeName = "";
        resumeNtkNextImageWorkId = "";
        resumeNtkNextImageEpisodeId = "";
        resumeNtkNextImageCount = 0;
    }

    /** Atomically freezes the six-field exact neighbor tuple for non-main reader workers. */
    public synchronized ResumeNtkNextEpisodeIdentitySnapshot snapshotResumeNtkNextEpisodeIdentity() {
        return new ResumeNtkNextEpisodeIdentitySnapshot(
                resumeNtkNextEpisodePath == null ? "" : resumeNtkNextEpisodePath.trim(),
                resumeNtkNextEpisodeId,
                resumeNtkNextEpisodeName == null ? "" : resumeNtkNextEpisodeName,
                resumeNtkNextImageWorkId == null ? "" : resumeNtkNextImageWorkId.trim(),
                resumeNtkNextImageEpisodeId == null ? "" : resumeNtkNextImageEpisodeId.trim(),
                Math.max(0, resumeNtkNextImageCount));
    }

    public void inheritMissingResumeNtkNextEpisodeIdentity(MTitle source) {
        if(source == null || hasCompleteResumeNtkNextEpisodeIdentity()
                || !getResumeNtkEpisodePath().equals(source.getResumeNtkEpisodePath())
                || !source.hasCompleteResumeNtkNextEpisodeIdentity())
            return;
        setResumeNtkNextEpisodeIdentity(
                source.getResumeNtkNextEpisodePath(),
                source.getResumeNtkNextEpisodeId(),
                source.getResumeNtkNextEpisodeName(),
                source.getResumeNtkNextImageWorkId(),
                source.getResumeNtkNextImageEpisodeId(),
                source.getResumeNtkNextImageCount());
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

    public static boolean isGenericNtkSiteTitle(String value) {
        if(value == null)
            return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if(normalized.length() == 0)
            return false;
        boolean ntkBrand = normalized.contains("뉴토끼")
                || normalized.contains("newtoki");
        boolean sitePreview = normalized.contains("웹툰 미리보기")
                || normalized.contains("만화 미리보기")
                || normalized.contains("webtoon preview")
                || normalized.contains("comic preview");
        return ntkBrand && sitePreview;
    }

    public static boolean isSuspiciousNtkThumbnail(String value) {
        if(value == null)
            return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if(lower.length() == 0)
            return false;
        return lower.contains("banner")
                || lower.contains("advert")
                || lower.contains("sponsor")
                || lower.contains("popup")
                || lower.contains("/ads/")
                || lower.contains("/ad/")
                || lower.contains("ad_banner")
                || lower.contains("ad-banner");
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
        clone.setResumeNtkImageIdentity(
                getResumeNtkImageWorkId(),
                getResumeNtkImageEpisodeId(),
                getResumeNtkImageCount());
        clone.setResumeNtkNextEpisodeIdentity(
                getResumeNtkNextEpisodePath(),
                getResumeNtkNextEpisodeId(),
                getResumeNtkNextEpisodeName(),
                getResumeNtkNextImageWorkId(),
                getResumeNtkNextImageEpisodeId(),
                getResumeNtkNextImageCount());
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
