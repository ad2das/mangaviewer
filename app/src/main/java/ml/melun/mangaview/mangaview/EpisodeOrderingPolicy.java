package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

final class EpisodeOrderingPolicy {
    private EpisodeOrderingPolicy() {
    }

    static void sortByVisibleEpisodeNumber(ArrayList<Manga> episodes) {
        if(episodes == null || episodes.size() < 2)
            return;
        int blockStart = -1;
        for(int i = 0; i <= episodes.size(); i++) {
            boolean sortable = i < episodes.size() && visibleEpisodeNumber(episodes.get(i)) >= 0;
            if(sortable) {
                if(blockStart < 0)
                    blockStart = i;
                continue;
            }
            if(blockStart >= 0) {
                sortEpisodeBlockByVisibleEpisodeNumber(episodes, blockStart, i);
                blockStart = -1;
            }
        }
    }

    static double visibleEpisodeNumber(Manga episode) {
        return episode == null ? -1 : episode.visibleEpisodeNumberResult().orderingValue;
    }

    static double visibleEpisodeNumber(String title) {
        return EpisodeNumberParser.parse(title).orderingValue;
    }

    private static void sortEpisodeBlockByVisibleEpisodeNumber(ArrayList<Manga> episodes, int start, int end) {
        if(end - start < 2)
            return;
        ArrayList<EpisodeOrder> block = new ArrayList<>();
        HashSet<Long> visibleNumbers = new HashSet<>();
        for(int i = start; i < end; i++) {
            double number = visibleEpisodeNumber(episodes.get(i));
            /*
             * A season can restart its displayed numbering while the source keeps one canonical
             * episode sequence.  Re-sorting such a block by the repeated display number turns a
             * canonical 312 -> 311 transition into 311 -> an old season's "60화".  The parser's
             * source order is the only unambiguous order once a visible number repeats, so keep it
             * intact.  Unique-number blocks still get the legacy repair below.
             */
            if(!visibleNumbers.add(Double.doubleToLongBits(number)))
                return;
            block.add(new EpisodeOrder(episodes.get(i), i - start, number));
        }
        Collections.sort(block, (left, right) -> {
            int numberCompare = Double.compare(right.number, left.number);
            if(numberCompare != 0)
                return numberCompare;
            return Integer.compare(left.originalIndex, right.originalIndex);
        });
        for(int i = 0; i < block.size(); i++)
            episodes.set(start + i, block.get(i).episode);
    }

    private static final class EpisodeOrder {
        final Manga episode;
        final int originalIndex;
        final double number;

        EpisodeOrder(Manga episode, int originalIndex, double number) {
            this.episode = episode;
            this.originalIndex = originalIndex;
            this.number = number;
        }
    }
}
