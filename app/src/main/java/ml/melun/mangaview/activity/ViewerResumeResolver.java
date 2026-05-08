package ml.melun.mangaview.activity;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

final class ViewerResumeResolver {
    private static final int MAX_BROAD_FALLBACKS = 36;

    private ViewerResumeResolver() {
    }

    static boolean shouldResolveBeforeDirectFetch(Manga target, Title title) {
        if(target == null || !target.isOnline() || title == null)
            return false;
        if(containsEpisode(title.getEps(), target))
            return false;
        int episodeCount = title.getEpisodeCount();
        if(episodeCount <= 0 && title.getEps() != null)
            episodeCount = title.getEps().size();
        return target.getId() > 0 && episodeCount > 0 && target.getId() <= episodeCount;
    }

    static List<Manga> candidates(Manga target, Title title, boolean skipTarget) {
        ArrayList<Manga> candidates = new ArrayList<>();
        if(!skipTarget)
            addCandidate(candidates, target, title);
        List<Manga> episodes = title == null ? null : title.getEps();
        if(episodes == null || episodes.size() == 0)
            return candidates;

        int exactIndex = findEpisodeIndex(episodes, target);
        if(exactIndex >= 0)
            addCandidate(candidates, episodes.get(exactIndex), title);

        int progressIndex = title.getBookmarkEpisodeIndex() - 1;
        addEpisodeAt(candidates, episodes, progressIndex, title);

        int computedIndex = title.getBookmarkIndex() - 1;
        addEpisodeAt(candidates, episodes, computedIndex, title);

        int legacyIndex = target == null ? -1 : target.getId() - 1;
        addEpisodeAt(candidates, episodes, legacyIndex, title);

        int center = exactIndex >= 0 ? exactIndex : progressIndex >= 0 ? progressIndex : legacyIndex;
        for(int distance = 1; center >= 0 && distance <= 4; distance++) {
            addEpisodeAt(candidates, episodes, center - distance, title);
            addEpisodeAt(candidates, episodes, center + distance, title);
        }

        // Older recent/bookmark records sometimes only contain a stale numeric id.
        // In that case prefer opening a real episode over showing a dead error popup.
        int added = 0;
        for(int i = 0; i < episodes.size() && added < MAX_BROAD_FALLBACKS; i++) {
            int before = candidates.size();
            addEpisodeAt(candidates, episodes, i, title);
            if(candidates.size() > before)
                added++;
        }
        return candidates;
    }

    static boolean sameManga(Manga a, Manga b) {
        if(a == null || b == null)
            return false;
        return a.getId() == b.getId() && a.getBaseMode() == b.getBaseMode();
    }

    private static boolean containsEpisode(List<Manga> episodes, Manga target) {
        return findEpisodeIndex(episodes, target) >= 0;
    }

    private static int findEpisodeIndex(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return -1;
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode != null && episode.getId() == target.getId() && episode.getBaseMode() == target.getBaseMode())
                return i;
        }
        return -1;
    }

    private static void addEpisodeAt(List<Manga> candidates, List<Manga> episodes, int index, Title title) {
        if(episodes == null || index < 0 || index >= episodes.size())
            return;
        addCandidate(candidates, episodes.get(index), title);
    }

    private static void addCandidate(List<Manga> candidates, Manga candidate, Title title) {
        if(candidate == null || !candidate.isOnline())
            return;
        if(title != null) {
            candidate.setTitle(title);
            candidate.setTitleId(title.getId());
            if(title.getEps() != null && title.getEps().size() > 0)
                candidate.setEps(title.getEps());
        }
        for(Manga existing : candidates)
            if(sameManga(existing, candidate))
                return;
        candidates.add(candidate);
    }
}
