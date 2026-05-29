package ml.melun.mangaview.activity;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

final class EpisodeCachePolicy {
    static final int MEMORY_CACHE_MAIN_THREAD_PARSE_MAX_CHARS = 64 * 1024;

    private EpisodeCachePolicy() {
    }

    static boolean shouldParseMemoryCacheOnMain(int jsonLength) {
        return jsonLength > 0 && jsonLength <= MEMORY_CACHE_MAIN_THREAD_PARSE_MAX_CHARS;
    }

    static ArrayList<Manga> normalizeEpisodeSnapshot(List<Manga> loadedEpisodes, Title title) {
        ArrayList<Manga> normalized = Title.orderedEpisodeSnapshot(loadedEpisodes);
        if(normalized == null)
            normalized = new ArrayList<>();
        if(title == null)
            return normalized;
        for(Manga episode : normalized) {
            if(episode == null)
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
        }
        return normalized;
    }

    static ArrayList<Manga> episodeCacheSnapshot(List<Manga> episodes) {
        ArrayList<Manga> snapshot = new ArrayList<>();
        if(episodes == null)
            return snapshot;
        ArrayList<Manga> orderedEpisodes = Title.orderedEpisodeSnapshot(episodes);
        if(orderedEpisodes == null)
            return snapshot;
        for(Manga episode : orderedEpisodes) {
            if(episode == null)
                continue;
            Manga copy = new Manga(episode.getId(), episode.getName(), episode.getDate(), episode.getBaseMode());
            copy.addThumb(episode.getThumb());
            copy.setMode(episode.getMode());
            copy.setTitleId(episode.getTitleId());
            copy.setNtkEpisodePath(episode.getNtkEpisodePath());
            copy.setNtkImageCount(episode.getNtkImageCount());
            copy.setOfflinePath(episode.getOfflinePath());
            snapshot.add(copy);
        }
        return snapshot;
    }

    static boolean sameEpisodeIdentityList(List<Manga> current, List<Manga> fresh) {
        if(current == null || fresh == null || current.size() != fresh.size())
            return false;
        for(int i = 0; i < current.size(); i++) {
            Manga left = current.get(i);
            Manga right = fresh.get(i);
            if(left == null || right == null) {
                if(left != right)
                    return false;
                continue;
            }
            if(left.getId() != right.getId() || left.getBaseMode() != right.getBaseMode())
                return false;
            String leftPath = left.getNtkEpisodePath();
            String rightPath = right.getNtkEpisodePath();
            if(leftPath != null && leftPath.length() > 0 && rightPath != null && rightPath.length() > 0
                    && !leftPath.equals(rightPath))
                return false;
        }
        return true;
    }

    static boolean isCompatibleCacheSource(String targetSource, String candidateSource) {
        String normalizedTarget = normalizeSource(targetSource);
        if(normalizedTarget.length() == 0)
            return true;
        return normalizedTarget.equals(normalizeSource(candidateSource));
    }

    static int cachedEpisodeTitleMatchScore(String titleName, List<Manga> episodes) {
        String normalizedTitleName = normalizeTitleName(titleName);
        if(normalizedTitleName.length() == 0 || episodes == null)
            return 0;
        int matches = 0;
        int checked = 0;
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            checked++;
            String episodeName = normalizeTitleName(episode.getName());
            if(episodeName.startsWith(normalizedTitleName))
                matches++;
            if(checked >= 20)
                break;
        }
        if(matches >= 2)
            return matches;
        return matches == 1 && episodes.size() == 1 ? 1 : 0;
    }

    static String normalizeTitleName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    static String normalizeSource(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

