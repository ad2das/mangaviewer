package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EpisodeOrderingPolicy {
    private static final Pattern EPISODE_WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern EPISODE_NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?(?:\\s*[,~～\\-]\\s*\\d+(?:\\.\\d+)?)*)\\s*화");
    private static final Pattern EPISODE_BLOCK_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

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
        return episode == null ? -1 : visibleEpisodeNumber(episode.getName());
    }

    static double visibleEpisodeNumber(String title) {
        if(title == null)
            return -1;
        String compact = EPISODE_WHITESPACE_PATTERN.matcher(title).replaceAll("");
        if(compact.contains("번외")
                || compact.contains("외전")
                || compact.contains("특별")
                || compact.contains("부록")
                || compact.contains("기록")
                || compact.contains("후기")
                || compact.contains("프롤로그"))
            return -1;
        Matcher episodeMatcher = EPISODE_NUMBER_PATTERN.matcher(title);
        double result = -1;
        while(episodeMatcher.find()) {
            double number = visibleEpisodeNumberBlockValue(episodeMatcher.group(1));
            if(number >= 0)
                result = Math.max(result, number);
        }
        return result;
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

    private static double visibleEpisodeNumberBlockValue(String block) {
        ArrayList<Double> numbers = new ArrayList<>();
        Matcher numberMatcher = EPISODE_BLOCK_NUMBER_PATTERN.matcher(block == null ? "" : block);
        while(numberMatcher.find()) {
            try {
                numbers.add(Double.parseDouble(numberMatcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        if(numbers.size() == 0)
            return -1;
        if(numbers.size() == 2 && isHyphenPartEpisode(block, numbers.get(0), numbers.get(1)))
            return numbers.get(0) + Math.min(numbers.get(1), 9999.0d) / 10000.0d;
        double result = -1;
        for(Double number : numbers)
            result = Math.max(result, number);
        return result;
    }

    private static boolean isHyphenPartEpisode(String value, double first, double second) {
        if(value == null || !value.contains("-"))
            return false;
        if(first != Math.floor(first) || second != Math.floor(second))
            return false;
        return first > 0 && second > 0 && second < first;
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
