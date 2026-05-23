package ml.melun.mangaview.activity;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.MTitle.base_auto;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

final class MissingEpisodeNavigator {
    private static final Pattern EPISODE_BLOCK_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?(?:\\s*[,~～\\-]\\s*\\d+(?:\\.\\d+)?)*)\\s*화");
    private static final Pattern EPISODE_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final double EPSILON = 0.0001;

    private MissingEpisodeNavigator() {
    }

    interface Host {
        void lockUi(boolean lock);
        void openAlternateEpisode(Title title, Manga episode);
        void showCaptcha(Manga episode);
        void onPromptCancelled();
    }

    static final class PromptState {
        private AlertDialog dialog;
        private PendingSwitch pendingSwitch;
        private final Set<String> acknowledged = new HashSet<>();

        void dismiss() {
            if(dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }
    }

    static boolean retryPendingAfterCaptcha(AppCompatActivity activity, PromptState state, Host host) {
        if(activity == null || state == null || host == null || state.pendingSwitch == null)
            return false;
        PendingSwitch pending = state.pendingSwitch;
        openAlternateEpisode(activity, pending.source, pending.gap, pending.alternateSource, state, host);
        return true;
    }

    static boolean maybePromptNextEpisode(AppCompatActivity activity, boolean dark, Manga source, Manga target,
                                          PromptState state, Host host, Runnable skipAction) {
        MissingEpisodeGap gap = missingNextEpisodeGap(source, target);
        if(gap == null || activity == null || state == null || host == null)
            return false;
        String key = gapKey(source, target, gap);
        if(state.acknowledged.contains(key))
            return false;
        if(state.dialog != null && state.dialog.isShowing())
            return true;

        String alternateSource = alternateSource(source);
        AlertDialog.Builder builder = dark
                ? new AlertDialog.Builder(activity, R.style.darkDialog)
                : new AlertDialog.Builder(activity);
        AlertDialog dialog = builder
                .setTitle("회차 누락")
                .setMessage("다음화가 누락되어있는데 " + sourceLabel(alternateSource) + "에서 마저 볼까요?")
                .setPositiveButton(sourceLabel(alternateSource) + "에서 보기", (d, which) -> {
                    state.acknowledged.add(key);
                    state.pendingSwitch = new PendingSwitch(source, gap, alternateSource);
                    openAlternateEpisode(activity, source, gap, alternateSource, state, host);
                })
                .setNegativeButton("그냥 다음화", (d, which) -> {
                    state.acknowledged.add(key);
                    if(skipAction != null)
                        skipAction.run();
                })
                .setNeutralButton("취소", (d, which) -> host.onPromptCancelled())
                .create();
        dialog.setOnDismissListener(d -> {
            if(state.dialog == d)
                state.dialog = null;
        });
        state.dialog = dialog;
        try {
            dialog.show();
        } catch (RuntimeException e) {
            state.dialog = null;
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
        return true;
    }

    static boolean hasMissingNextEpisodeGap(Manga source, Manga target) {
        return missingNextEpisodeGap(source, target) != null;
    }

    static boolean shouldPromptMissingNextEpisodeForTest(String sourceName, String targetName) {
        EpisodeNumberRange source = episodeNumberRange(sourceName);
        EpisodeNumberRange target = episodeNumberRange(targetName);
        return missingNextEpisodeGap(source, target) != null;
    }

    private static void openAlternateEpisode(AppCompatActivity activity, Manga source, MissingEpisodeGap gap,
                                             String alternateSource, PromptState state, Host host) {
        host.lockUi(true);
        AppDispatchers.submitUserAction(() -> {
            SavedSiteConfig saved = SavedSiteConfig.capture();
            AlternateEpisodeResult result;
            try {
                applySource(alternateSource);
                result = findAlternateEpisode(source, gap, alternateSource);
                if(result == null)
                    result = AlternateEpisodeResult.error("다른 소스에서 누락된 다음화를 찾지 못했습니다.");
                if(!result.success && !result.captcha)
                    saved.restore();
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                if(getHttpClient().hasRecentCloudflareChallenge())
                    result = AlternateEpisodeResult.captcha(null, null);
                else {
                    saved.restore();
                    result = AlternateEpisodeResult.error("다른 소스에서 누락된 다음화를 찾지 못했습니다.");
                }
            }
            AlternateEpisodeResult finalResult = result;
            AppDispatchers.runOnMain(() -> {
                if(!Utils.canUseContextForUi(activity))
                    return;
                host.lockUi(false);
                if(finalResult.captcha) {
                    host.showCaptcha(finalResult.episode);
                    return;
                }
                if(state != null)
                    state.pendingSwitch = null;
                if(finalResult.success) {
                    host.openAlternateEpisode(finalResult.title, finalResult.episode);
                    return;
                }
                Utils.safeToast(activity, finalResult.message, Toast.LENGTH_SHORT);
            });
        });
    }

    private static AlternateEpisodeResult findAlternateEpisode(Manga source, MissingEpisodeGap gap, String alternateSource) throws Exception {
        Title sourceTitle = sourceTitle(source);
        String query = searchQuery(source, sourceTitle);
        if(query.length() == 0)
            return AlternateEpisodeResult.error("다른 소스에서 검색할 제목을 찾지 못했습니다.");

        long startedAt = System.currentTimeMillis();
        Search search = MangaRepository.createSearch(query, 0, searchBaseMode(source, sourceTitle));
        int searchResult = MangaRepository.search(search, MangaRepository.cancellation());
        if(getHttpClient().hasCloudflareChallengeSince(startedAt))
            return AlternateEpisodeResult.captcha(null, null);
        if(searchResult != LOAD_OK)
            return AlternateEpisodeResult.error("다른 소스 검색에 실패했습니다.");

        Title alternateTitle = bestMatchingTitle(search.getResult(), query, alternateSource);
        if(alternateTitle == null) {
            if(getHttpClient().hasCloudflareChallengeSince(startedAt))
                return AlternateEpisodeResult.captcha(null, null);
            return AlternateEpisodeResult.error("다른 소스에서 같은 작품을 찾지 못했습니다.");
        }
        alternateTitle.setSourceSite(alternateSource);

        startedAt = System.currentTimeMillis();
        int episodeResult = MangaRepository.fetchEpisodesForeground(alternateTitle);
        if(episodeResult == LOAD_CAPTCHA)
            return AlternateEpisodeResult.captcha(alternateTitle, null);
        if(getHttpClient().hasCloudflareChallengeSince(startedAt))
            return AlternateEpisodeResult.captcha(alternateTitle, null);
        if(episodeResult != LOAD_OK)
            return AlternateEpisodeResult.error("다른 소스의 회차 목록을 불러오지 못했습니다.");

        ArrayList<Manga> episodes = Utils.snapshotEpisodes(alternateTitle);
        if(episodes == null || episodes.size() == 0)
            return AlternateEpisodeResult.error("다른 소스에 회차 목록이 없습니다.");
        for(Manga episode : episodes)
            attachTitle(alternateTitle, episode);

        Manga episode = findMissingEpisode(episodes, gap);
        if(episode == null)
            return AlternateEpisodeResult.error("다른 소스에서 누락된 다음화를 찾지 못했습니다.");
        attachTitle(alternateTitle, episode);
        episode.setEps(episodes);
        startedAt = System.currentTimeMillis();
        int viewerResult = MangaRepository.fetchViewerInitial(episode, MangaRepository.cancellation());
        if(viewerResult == LOAD_CAPTCHA)
            return AlternateEpisodeResult.captcha(alternateTitle, episode);
        if(getHttpClient().hasCloudflareChallengeSince(startedAt))
            return AlternateEpisodeResult.captcha(alternateTitle, episode);
        if(viewerResult != LOAD_OK || MangaRepository.imageUrls(episode, null).size() == 0)
            return AlternateEpisodeResult.error("다른 소스의 회차 이미지를 불러오지 못했습니다.");
        return AlternateEpisodeResult.success(alternateTitle, episode);
    }

    private static Manga findMissingEpisode(ArrayList<Manga> episodes, MissingEpisodeGap gap) {
        if(episodes == null || gap == null)
            return null;
        double expected = Math.floor(gap.source.max) + 1.0d;
        Manga fallback = null;
        EpisodeNumberRange fallbackRange = null;
        for(Manga episode : episodes) {
            EpisodeNumberRange range = episodeNumberRange(episode);
            if(range == null)
                continue;
            if(range.min - EPSILON <= expected && expected <= range.max + EPSILON)
                return episode;
            if(range.min > gap.source.max + EPSILON && range.min < gap.target.min - EPSILON) {
                if(fallback == null || range.min < fallbackRange.min) {
                    fallback = episode;
                    fallbackRange = range;
                }
            }
        }
        return fallback;
    }

    private static MissingEpisodeGap missingNextEpisodeGap(Manga source, Manga target) {
        return missingNextEpisodeGap(episodeNumberRange(source), episodeNumberRange(target));
    }

    private static MissingEpisodeGap missingNextEpisodeGap(EpisodeNumberRange source, EpisodeNumberRange target) {
        if(source == null || target == null)
            return null;
        if(target.min > source.max + 1.0d + EPSILON)
            return new MissingEpisodeGap(source, target);
        return null;
    }

    private static EpisodeNumberRange episodeNumberRange(Manga episode) {
        return episode == null ? null : episodeNumberRange(episode.getName());
    }

    private static EpisodeNumberRange episodeNumberRange(String title) {
        if(title == null)
            return null;
        String compact = title.replaceAll("\\s+", "");
        if(compact.contains("번외")
                || compact.contains("외전")
                || compact.contains("특별")
                || compact.contains("부록")
                || compact.contains("기록")
                || compact.contains("후기")
                || compact.contains("프롤로그"))
            return null;

        Matcher episodeMatcher = EPISODE_BLOCK_PATTERN.matcher(title);
        EpisodeNumberRange result = null;
        while(episodeMatcher.find()) {
            Matcher numberMatcher = EPISODE_NUMBER_PATTERN.matcher(episodeMatcher.group(1));
            while(numberMatcher.find()) {
                try {
                    double number = Double.parseDouble(numberMatcher.group());
                    if(result == null)
                        result = new EpisodeNumberRange(number, number);
                    else
                        result = new EpisodeNumberRange(Math.min(result.min, number), Math.max(result.max, number));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private static String alternateSource(Manga source) {
        String current = sourceSite(source);
        if("ntk".equals(current))
            return "wfwf";
        return "ntk";
    }

    private static String sourceSite(Manga source) {
        Title sourceTitle = sourceTitle(source);
        if(sourceTitle != null && sourceTitle.getSourceSite().length() > 0)
            return sourceTitle.getSourceSite();
        return p != null && p.isNtkSite() ? "ntk" : "wfwf";
    }

    private static Title sourceTitle(Manga source) {
        return source == null ? null : source.getTitle();
    }

    private static String searchQuery(Manga source, Title sourceTitle) {
        if(sourceTitle != null && sourceTitle.getName() != null && sourceTitle.getName().trim().length() > 0)
            return sourceTitle.getName().trim();
        if(source == null || source.getName() == null)
            return "";
        String name = source.getName()
                .replaceFirst("^\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)\\s*", "")
                .replaceAll("\\s*\\d+(?:\\.\\d+)?(?:\\s*[,~～\\-]\\s*\\d+(?:\\.\\d+)?)*\\s*화.*$", "")
                .trim();
        return name;
    }

    private static int searchBaseMode(Manga source, Title sourceTitle) {
        int baseMode = sourceTitle == null ? base_auto : sourceTitle.getBaseMode();
        if(baseMode == base_auto && source != null)
            baseMode = source.getBaseMode();
        return baseMode == base_auto ? base_comic : baseMode;
    }

    private static Title bestMatchingTitle(ArrayList<Title> titles, String query, String alternateSource) {
        if(titles == null || titles.size() == 0)
            return null;
        String queryKey = titleKey(query);
        Title best = null;
        int bestScore = Integer.MIN_VALUE;
        for(Title candidate : titles) {
            if(candidate == null)
                continue;
            String source = candidate.getSourceSite();
            int score = "ntk".equals(alternateSource) == "ntk".equals(source) ? 4 : 0;
            String candidateKey = titleKey(candidate.getName());
            if(candidateKey.equals(queryKey))
                score += 8;
            else if(candidateKey.contains(queryKey) || queryKey.contains(candidateKey))
                score += 4;
            if(candidate.getBaseMode() == base_auto)
                score -= 1;
            if(score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static String titleKey(String value) {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*\\]", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9a-z가-힣]", "");
    }

    private static void attachTitle(Title title, Manga episode) {
        if(title == null || episode == null)
            return;
        episode.setTitle(title);
        episode.setTitleId(title.getId());
    }

    private static void applySource(String source) {
        if(p == null)
            return;
        if("ntk".equals(source))
            p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        else
            p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
    }

    private static String sourceLabel(String source) {
        return "ntk".equals(source) ? "NTK" : "WFWF";
    }

    private static String gapKey(Manga source, Manga target, MissingEpisodeGap gap) {
        return safeIdentity(source)
                + ">"
                + safeIdentity(target)
                + ":"
                + gap.source.max
                + ">"
                + gap.target.min;
    }

    private static String safeIdentity(Manga manga) {
        if(manga == null)
            return "";
        return manga.getBaseMode()
                + ":"
                + manga.getTitleId()
                + ":"
                + manga.getId()
                + ":"
                + safeName(manga);
    }

    private static String safeName(Manga manga) {
        String name = manga == null ? "" : manga.getName();
        return name == null ? "" : name;
    }

    private static final class EpisodeNumberRange {
        final double min;
        final double max;

        EpisodeNumberRange(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class MissingEpisodeGap {
        final EpisodeNumberRange source;
        final EpisodeNumberRange target;

        MissingEpisodeGap(EpisodeNumberRange source, EpisodeNumberRange target) {
            this.source = source;
            this.target = target;
        }
    }

    private static final class PendingSwitch {
        final Manga source;
        final MissingEpisodeGap gap;
        final String alternateSource;

        PendingSwitch(Manga source, MissingEpisodeGap gap, String alternateSource) {
            this.source = source;
            this.gap = gap;
            this.alternateSource = alternateSource;
        }
    }

    private static final class AlternateEpisodeResult {
        final boolean success;
        final boolean captcha;
        final Title title;
        final Manga episode;
        final String message;

        private AlternateEpisodeResult(boolean success, boolean captcha, Title title, Manga episode, String message) {
            this.success = success;
            this.captcha = captcha;
            this.title = title;
            this.episode = episode;
            this.message = message;
        }

        static AlternateEpisodeResult success(Title title, Manga episode) {
            return new AlternateEpisodeResult(true, false, title, episode, "");
        }

        static AlternateEpisodeResult captcha(Title title, Manga episode) {
            return new AlternateEpisodeResult(false, true, title, episode, "");
        }

        static AlternateEpisodeResult error(String message) {
            return new AlternateEpisodeResult(false, false, null, null, message);
        }
    }

    private static final class SavedSiteConfig {
        final String defUrl;
        final String url;
        final String webtoonUrl;
        final boolean ntk;

        private SavedSiteConfig(String defUrl, String url, String webtoonUrl, boolean ntk) {
            this.defUrl = defUrl;
            this.url = url;
            this.webtoonUrl = webtoonUrl;
            this.ntk = ntk;
        }

        static SavedSiteConfig capture() {
            if(p == null)
                return new SavedSiteConfig("", "", "", false);
            return new SavedSiteConfig(p.getDefUrl(), p.getUrl(), p.getWebtoonUrl(), p.isNtkSite());
        }

        void restore() {
            if(p == null)
                return;
            if(ntk)
                p.setNtkSitePreset(webtoonUrl);
            else {
                p.setSitePreset(defUrl, webtoonUrl);
                p.setUrl(url);
            }
        }
    }
}
