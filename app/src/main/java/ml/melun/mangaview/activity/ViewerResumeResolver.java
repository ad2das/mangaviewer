package ml.melun.mangaview.activity;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.p;

public final class ViewerResumeResolver {
    private static final int MAX_BROAD_FALLBACKS = 36;
    private static final int LARGE_NTK_EPISODE_COUNT = 300;

    private ViewerResumeResolver() {
    }

    public static boolean shouldResolveBeforeDirectFetch(Manga target, Title title) {
        if(target == null || !target.isOnline() || title == null)
            return false;
        if(shouldBlockPathlessNtkResume(target, title))
            return true;
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        if(containsEpisode(episodes, target))
            return false;
        int episodeCount = title.getEpisodeCount();
        if(episodeCount <= 0)
            episodeCount = episodes.size();
        return target.getId() > 0 && episodeCount > 0 && target.getId() <= episodeCount;
    }

    public static boolean shouldUseTargetAsLastResort(Manga target, Title title) {
        return !shouldBlockPathlessNtkResume(target, title);
    }

    public static Manga concreteNtkResumeCandidate(Manga target, Title title) {
        if(!shouldBlockPathlessNtkResume(target, title))
            return target;
        List<Manga> candidates = candidates(target, title, true);
        for(Manga candidate : candidates) {
            if(candidate != null && candidate.getNtkEpisodePath().length() > 0)
                return candidate;
        }
        return null;
    }

    public static boolean shouldBlockPathlessNtkResume(Manga target, Title title) {
        if(target == null || !target.isOnline())
            return false;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null || !"ntk".equals(currentTitle.getSourceSite()))
            return false;
        if(target.getNtkEpisodePath().length() > 0)
            return false;
        return isMinimalResumeTarget(target);
    }

    public static List<Manga> candidates(Manga target, Title title, boolean skipTarget) {
        ArrayList<Manga> candidates = new ArrayList<>();
        List<Manga> episodes = title == null ? null : Utils.snapshotEpisodes(title);
        if(episodes == null || episodes.size() == 0) {
            if(!skipTarget)
                addCandidate(candidates, target, title);
            return candidates;
        }

        int exactIndex = findEpisodeIndex(episodes, target);
        if(exactIndex >= 0)
            addCandidate(candidates, episodes.get(exactIndex), title);

        boolean pathlessNtkResume = skipTarget && shouldBlockPathlessNtkResume(target, title);
        if(pathlessNtkResume)
            addVisibleNumberCandidate(candidates, episodes, target, title);

        int progressIndex = title.getBookmarkEpisodeIndex() - 1;
        addEpisodeAt(candidates, episodes, progressIndex, title);

        int computedIndex = title.getBookmarkIndex() - 1;
        addEpisodeAt(candidates, episodes, computedIndex, title);

        if(skipTarget && !pathlessNtkResume)
            addVisibleNumberCandidate(candidates, episodes, target, title);

        if(!skipTarget)
            addCandidate(candidates, target, title);

        int legacyIndex = target == null ? -1 : target.getId() - 1;
        addEpisodeAt(candidates, episodes, legacyIndex, title);

        int center = exactIndex >= 0 ? exactIndex : progressIndex >= 0 ? progressIndex : legacyIndex;
        for(int distance = 1; center >= 0 && distance <= 4; distance++) {
            addEpisodeAt(candidates, episodes, center - distance, title);
            addEpisodeAt(candidates, episodes, center + distance, title);
        }

        // Older recent/bookmark records sometimes only contain a stale numeric id.
        // In that case prefer opening a real episode over showing a dead error popup.
        if(skipTarget && isLargeNtkTitle(title, episodes))
            return candidates;

        int added = 0;
        for(int i = 0; i < episodes.size() && added < MAX_BROAD_FALLBACKS; i++) {
            int before = candidates.size();
            addEpisodeAt(candidates, episodes, i, title);
            if(candidates.size() > before)
                added++;
        }
        return candidates;
    }

    public static List<Manga> nearbyCandidates(Manga target, Title title, boolean skipTarget) {
        ArrayList<Manga> candidates = new ArrayList<>();
        if(!skipTarget)
            addNearbyCandidate(candidates, target, title);
        List<Manga> episodes = title == null ? null : Utils.snapshotEpisodes(title);
        if(episodes == null || episodes.size() == 0)
            return candidates;

        int exactIndex = -1;
        if(target != null) {
            for(int i = 0; i < episodes.size(); i++) {
                Manga episode = episodes.get(i);
                if(episode != null && episode.getId() == target.getId()
                        && episode.getBaseMode() == target.getBaseMode()) {
                    exactIndex = i;
                    addNearbyCandidate(candidates, episode, title);
                    break;
                }
            }
        }

        int progressIndex = title.getBookmarkEpisodeIndex() - 1;
        addNearbyEpisodeAt(candidates, episodes, progressIndex, title);
        int computedIndex = title.getBookmarkIndex() - 1;
        addNearbyEpisodeAt(candidates, episodes, computedIndex, title);

        int center = exactIndex >= 0 ? exactIndex : progressIndex;
        for(int distance = 1; center >= 0 && distance <= 2; distance++) {
            addNearbyEpisodeAt(candidates, episodes, center - distance, title);
            addNearbyEpisodeAt(candidates, episodes, center + distance, title);
        }
        return candidates;
    }

    public static int resolveBookmark(Title title) {
        if(title == null)
            return -1;
        if(p != null)
            p.ensureSourceSiteForTitle(title);
        int bookmark = p == null ? -1 : p.getBookmark(title);
        if(bookmark <= 0)
            bookmark = title.getBookmark();
        if(bookmark <= 0)
            bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0 && p != null)
            bookmark = p.getStoredProgressBookmark(title);
        if(bookmark > 0)
            title.setBookmark(bookmark);
        return bookmark;
    }

    public static Manga resumeManga(Title title) {
        int bookmark = resolveBookmark(title);
        if(title == null || bookmark <= 0)
            return null;
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        Manga resolved = findEpisodeById(episodes, bookmark);
        if(resolved == null) {
            int progressIndex = title.getBookmarkEpisodeIndex();
            if(progressIndex <= 0)
                progressIndex = title.getBookmarkIndex();
            if(progressIndex <= 0 && bookmark <= episodes.size())
                progressIndex = bookmark;
            resolved = episodeAt(episodes, progressIndex);
        }
        if(resolved == null)
            resolved = new Manga(bookmark, "", "", title.getBaseMode());
        resolved.setTitle(title);
        resolved.setTitleId(title.getId());
        if(resolved.getNtkEpisodePath().length() == 0 && "ntk".equals(title.getSourceSite())) {
            String resumePath = title.getResumeNtkEpisodePath();
            if(resumePath.length() == 0)
                resumePath = restoreNumericNtkResumePath(title);
            if(resumePath.length() > 0)
                resolved.setNtkEpisodePath(resumePath);
        }
        if("ntk".equals(title.getSourceSite())
                && resolved.getNtkEpisodePath().equals(title.getResumeNtkEpisodePath())) {
            if(resolved.getNtkImageWorkId().length() == 0)
                resolved.setNtkImageWorkId(title.getResumeNtkImageWorkId());
            if(resolved.getNtkImageEpisodeId().length() == 0)
                resolved.setNtkImageEpisodeId(title.getResumeNtkImageEpisodeId());
            if(resolved.getNtkImageCount() <= 0)
                resolved.setNtkImageCount(title.getResumeNtkImageCount());
        }
        if(episodes.size() > 0)
            resolved.setEps(episodes);
        return resolved;
    }

    /**
     * Older recent-history rows can contain the authoritative NTK image identity while missing
     * the equivalent viewer route.  The numeric NTK APIs use the same source work/episode ids in
     * that route, so reconstructing it is exact and avoids treating a valid home resume as an
     * unresolvable placeholder.
     */
    private static String restoreNumericNtkResumePath(Title title) {
        if(title == null || !"ntk".equals(title.getSourceSite()))
            return "";
        String workId = title.getResumeNtkImageWorkId();
        String episodeId = title.getResumeNtkImageEpisodeId();
        if(!isPositiveNumericIdentity(workId) || !isPositiveNumericIdentity(episodeId))
            return "";
        // Some slug-based webtoons use a separate upstream image work id. It is not a viewer
        // route and must never be substituted for the title's source work id.
        if(title.getId() <= 0 || !String.valueOf(title.getId()).equals(workId))
            return "";
        String segment = title.getBaseMode() == ml.melun.mangaview.mangaview.MTitle.base_webtoon
                ? "webtoon" : "manhwa";
        String path = "/" + segment + "/" + workId + "/" + episodeId;
        int imageCount = title.getResumeNtkImageCount();
        title.setResumeNtkEpisodePath(path);
        title.setResumeNtkImageIdentity(workId, episodeId, imageCount);
        return path;
    }

    private static boolean isPositiveNumericIdentity(String value) {
        if(value == null || !value.matches("\\d{1,12}"))
            return false;
        try {
            return Long.parseLong(value) > 0L;
        } catch(NumberFormatException ignored) {
            return false;
        }
    }

    public static boolean sameManga(Manga a, Manga b) {
        return Manga.sameEpisodeIdentity(a, b);
    }

    private static boolean isMinimalResumeTarget(Manga target) {
        String name = target.getName();
        if(name != null && name.trim().length() > 0)
            return false;
        try {
            List<String> images = target.getImgs(null);
            return images == null || images.size() == 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean containsEpisode(List<Manga> episodes, Manga target) {
        return findEpisodeIndex(episodes, target) >= 0;
    }

    private static int findEpisodeIndex(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return -1;
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(Manga.sameEpisodeIdentity(episode, target))
                return i;
        }
        return -1;
    }

    private static Manga findEpisodeById(List<Manga> episodes, int bookmark) {
        if(episodes == null || bookmark <= 0)
            return null;
        for(Manga episode : episodes)
            if(episode != null && episode.getId() == bookmark)
                return episode;
        return null;
    }

    private static void addVisibleNumberCandidate(List<Manga> candidates, List<Manga> episodes, Manga target, Title title) {
        if(episodes == null || target == null || target.getId() <= 0)
            return;
        String targetNumber = String.valueOf(target.getId());
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            String episodeNumber = Manga.visibleEpisodeNumberKey(episode.getName());
            if(!targetNumber.equals(episodeNumber))
                continue;
            addCandidate(candidates, episode, title);
            return;
        }
    }

    private static boolean isLargeNtkTitle(Title title, List<Manga> episodes) {
        return title != null
                && "ntk".equals(title.getSourceSite())
                && episodes != null
                && episodes.size() >= LARGE_NTK_EPISODE_COUNT;
    }

    private static Manga episodeAt(List<Manga> episodes, int oneBasedIndex) {
        if(episodes == null || oneBasedIndex <= 0 || oneBasedIndex > episodes.size())
            return null;
        return episodes.get(oneBasedIndex - 1);
    }

    private static void addEpisodeAt(List<Manga> candidates, List<Manga> episodes, int index, Title title) {
        if(episodes == null || index < 0 || index >= episodes.size())
            return;
        addCandidate(candidates, episodes.get(index), title);
    }

    private static void addNearbyEpisodeAt(List<Manga> candidates, List<Manga> episodes, int index, Title title) {
        if(episodes == null || index < 0 || index >= episodes.size())
            return;
        addNearbyCandidate(candidates, episodes.get(index), title);
    }

    private static void addCandidate(List<Manga> candidates, Manga candidate, Title title) {
        if(candidate == null || !candidate.isOnline())
            return;
        if(title != null) {
            candidate.setTitle(title);
            candidate.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                candidate.setEps(episodes);
        }
        for(Manga existing : candidates)
            if(sameManga(existing, candidate))
                return;
        candidates.add(candidate);
    }

    private static void addNearbyCandidate(List<Manga> candidates, Manga candidate, Title title) {
        if(candidate == null || !candidate.isOnline())
            return;
        Title currentTitle = title != null ? title : candidate.getTitle();
        if(currentTitle != null) {
            candidate.setTitle(currentTitle);
            candidate.setTitleId(currentTitle.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(currentTitle);
            if(episodes.size() > 0)
                candidate.setEps(episodes);
        }
        for(Manga existing : candidates)
            if(existing != null && existing.getId() == candidate.getId()
                    && existing.getBaseMode() == candidate.getBaseMode())
                return;
        candidates.add(candidate);
    }
}

