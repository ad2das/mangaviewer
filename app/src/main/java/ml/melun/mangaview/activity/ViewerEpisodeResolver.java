package ml.melun.mangaview.activity;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

final class ViewerEpisodeResolver {
    interface EpisodeMatcher {
        boolean sameEpisode(Manga first, Manga second);
    }

    private ViewerEpisodeResolver() {
    }

    static List<Manga> episodeListFor(Manga current, List<Manga> viewerEpisodes, Title title) {
        List<Manga> data = null;
        Title currentTitle = title != null ? title : (current == null ? null : current.getTitle());
        if(current != null)
            data = largerMatchingEpisodeList(data, current.getEps(), currentTitle);
        data = largerMatchingEpisodeList(data, viewerEpisodes, currentTitle);
        if(currentTitle != null)
            currentTitle.ensureProgressEpisodes(current);
        if(currentTitle != null)
            data = largerMatchingEpisodeList(data, currentTitle.getEps(), currentTitle);
        return data;
    }

    static Manga nextCandidate(Manga current, List<Manga> viewerEpisodes, Title title, EpisodeMatcher matcher) {
        if(current == null)
            return null;
        List<Manga> data = episodeListFor(current, viewerEpisodes, title);
        int index = findEpisodeIndex(data, current, matcher);
        Manga adjacent = null;
        for(int i = index - 1; i >= 0; i--) {
            Manga candidate = prepareCandidate(Utils.safeGet(data, i), current, viewerEpisodes, title);
            if(candidate != null && !matcher.sameEpisode(candidate, current)) {
                adjacent = candidate;
                break;
            }
        }
        Manga candidate = prepareCandidate(Manga.preferCloserVisibleEpisode(data, current, adjacent, true), current, viewerEpisodes, title);
        if(candidate != null && !matcher.sameEpisode(candidate, current))
            return candidate;
        candidate = current.nextEp();
        if(candidate != null) {
            candidate = prepareCandidate(candidate, current, viewerEpisodes, title);
            if(!matcher.sameEpisode(candidate, current))
                return candidate;
        }
        return null;
    }

    static Manga previousCandidate(Manga current, List<Manga> viewerEpisodes, Title title, EpisodeMatcher matcher) {
        if(current == null)
            return null;
        List<Manga> data = episodeListFor(current, viewerEpisodes, title);
        int index = findEpisodeIndex(data, current, matcher);
        Manga adjacent = null;
        if(data != null && index >= 0) {
            for(int i = index + 1; i < data.size(); i++) {
                Manga candidate = prepareCandidate(Utils.safeGet(data, i), current, viewerEpisodes, title);
                if(candidate != null && !matcher.sameEpisode(candidate, current)) {
                    adjacent = candidate;
                    break;
                }
            }
        }
        Manga candidate = prepareCandidate(Manga.preferCloserVisibleEpisode(data, current, adjacent, false), current, viewerEpisodes, title);
        if(candidate != null && !matcher.sameEpisode(candidate, current))
            return candidate;
        candidate = current.prevEp();
        if(candidate != null) {
            candidate = prepareCandidate(candidate, current, viewerEpisodes, title);
            if(!matcher.sameEpisode(candidate, current))
                return candidate;
        }
        return null;
    }

    static Manga prepareCandidate(Manga candidate, Manga source, List<Manga> viewerEpisodes, Title title) {
        if(candidate == null)
            return null;
        Title currentTitle = title != null ? title : (source == null ? null : source.getTitle());
        if(currentTitle != null) {
            candidate.setTitle(currentTitle);
            candidate.setTitleId(currentTitle.getId());
            List<Manga> episodes = episodeListFor(source, viewerEpisodes, title);
            if(episodes != null && episodes.size() > 0)
                candidate.setEps(episodes);
        }
        return candidate;
    }

    static int findEpisodeIndex(List<Manga> data, Manga current, EpisodeMatcher matcher) {
        if(data == null || current == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < data.size(); i++) {
            Manga candidate = data.get(i);
            if(candidate != null && matcher.sameEpisode(candidate, current))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private static List<Manga> largerMatchingEpisodeList(List<Manga> current, List<Manga> candidate, Title title) {
        if(candidate == null || candidate.size() == 0)
            return current;
        if(!matchesTitle(candidate, title))
            return current;
        if(current == null || candidate.size() > current.size())
            return candidate;
        return current;
    }

    private static boolean matchesTitle(List<Manga> episodes, Title title) {
        if(title == null || title.getId() <= 0)
            return true;
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            if(episode.getTitleId() > 0 && episode.getTitleId() != title.getId())
                return false;
            Title episodeTitle = episode.getTitle();
            if(episodeTitle != null && episodeTitle.getId() > 0 && episodeTitle.getId() != title.getId())
                return false;
        }
        return true;
    }
}

