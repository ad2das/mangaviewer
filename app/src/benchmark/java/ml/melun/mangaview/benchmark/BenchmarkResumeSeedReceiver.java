package ml.melun.mangaview.benchmark;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

/** Benchmark-build-only cold-state seed for the production home Continue card. */
public final class BenchmarkResumeSeedReceiver extends BroadcastReceiver {
    public static final String ACTION_SEED_RESUME =
            "ml.melun.mangaview.benchmark.SEED_RESUME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent == null || !ACTION_SEED_RESUME.equals(intent.getAction())) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("error:unsupported_action");
            return;
        }
        try {
            String workType = required(intent, "workType");
            String workId = required(intent, "workId");
            String workTitleBase64 = required(intent, "workTitleBase64");
            String workTitle = requiredDecoded(intent, "workTitleBase64");
            String episodeTitle = optionalDecoded(intent, "episodeTitleBase64");
            String currentPath = required(intent, "currentPath");
            String nextPath = required(intent, "nextPath");
            String p0SignalAction = required(intent, "p0SignalAction");
            String p0SignalNonce = required(intent, "p0SignalNonce");
            String p0SignalCaseId = required(intent, "p0SignalCaseId");
            String siteRoot = required(intent, "siteRoot");
            int currentPageCount = positive(intent, "currentPageCount");
            int nextPageCount = positive(intent, "nextPageCount");
            int resumePage = intent.getIntExtra("resumePage", -1);
            int resumeOffset = intent.getIntExtra("resumeOffset", -420);
            if(resumePage < 0 || resumePage >= currentPageCount)
                throw new IllegalArgumentException("resumePage outside current manifest");
            if(!p0SignalNonce.matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("invalid p0 signal nonce");
            if(!p0SignalAction.equals(
                    BenchmarkAdjacentCommitSignal.ACTION_PREFIX + p0SignalNonce))
                throw new IllegalArgumentException("invalid p0 signal action");

            int baseMode;
            if("webtoon".equals(workType)) baseMode = MTitle.base_webtoon;
            else if("manhwa".equals(workType)) baseMode = MTitle.base_comic;
            else throw new IllegalArgumentException("unsupported workType=" + workType);

            int titleId = stablePositiveId(workId);
            int currentId = stablePositiveId(lastSegment(currentPath));
            int nextId = stablePositiveId(lastSegment(nextPath));
            Title title = new Title(
                    workTitle, "", "", null, "", titleId, baseMode);
            title.setSourceSite("ntk");
            title.setResumeNtkEpisodePath(currentPath);
            title.setResumeNtkImageIdentity(workId, lastSegment(currentPath), currentPageCount);
            title.setBookmark(currentId);
            title.setReadingProgress(currentId, 2, 2);

            Manga current = episode(
                    currentId,
                    episodeTitle == null ? "" : episodeTitle,
                    baseMode,
                    title,
                    currentPath,
                    workId,
                    currentPageCount);
            Manga next = episode(
                    nextId,
                    "",
                    baseMode,
                    title,
                    nextPath,
                    workId,
                    nextPageCount);
            title.setResumeNtkNextEpisodeIdentity(next);
            ArrayList<Manga> episodes = new ArrayList<>();
            episodes.add(next);
            episodes.add(current);
            title.setEps(episodes);
            current.setEps(episodes);
            next.setEps(episodes);

            Preference preference = MainApplication.p;
            if(preference == null)
                throw new IllegalStateException("Preference was not initialized");
            preference.runWithoutSync(() -> {
                preference.setBaseMode(baseMode);
                preference.setNtkSitePreset(siteRoot);
                preference.setViewerBookmark(current, resumePage, resumeOffset, 0);
                preference.addRecent(title);
                preference.setBookmark(title, currentId);
            });
            // Preference uses apply() for normal UX. A final marker commit on the same backing
            // SharedPreferences serializes those writes before the host force-stops this process.
            boolean committed = preference.getSharedPref().edit()
                    .putLong("benchmarkResumeSeedCommittedAtMs", SystemClock.elapsedRealtime())
                    .commit();
            if(!committed) throw new IllegalStateException("preference commit failed");
            boolean signalCommitted = context.getSharedPreferences(
                            BenchmarkAdjacentCommitSignal.PREFS_NAME,
                            Context.MODE_PRIVATE)
                    .edit()
                    .putString(BenchmarkAdjacentCommitSignal.PREF_ACTION, p0SignalAction)
                    .putString(BenchmarkAdjacentCommitSignal.PREF_NONCE, p0SignalNonce)
                    .putString(BenchmarkAdjacentCommitSignal.PREF_CASE_ID, p0SignalCaseId)
                    .putString(
                            BenchmarkAdjacentCommitSignal.PREF_EXPECTED_EPISODE_PATH,
                            nextPath)
                    .commit();
            if(!signalCommitted)
                throw new IllegalStateException("p0 signal seed commit failed");
            setResultCode(Activity.RESULT_OK);
            setResultData("seeded:" + resumePage + ":" + currentPath +
                    ":titleBase64=" + encode(workTitle) + ":p0Signal=armed");
        } catch(Throwable failure) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("error:" + failure.getClass().getSimpleName() + ':' +
                    String.valueOf(failure.getMessage()));
        }
    }

    private static Manga episode(
            int id,
            String name,
            int baseMode,
            Title title,
            String path,
            String workId,
            int pageCount) {
        Manga manga = new Manga(id, name, "", baseMode);
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        manga.setNtkEpisodePath(path);
        manga.setNtkImageWorkId(workId);
        manga.setNtkImageEpisodeId(lastSegment(path));
        manga.setNtkImageCount(pageCount);
        return manga;
    }

    private static String required(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if(value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("missing " + key);
        return value.trim();
    }

    private static int positive(Intent intent, String key) {
        int value = intent.getIntExtra(key, 0);
        if(value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static String requiredDecoded(Intent intent, String key) {
        String value = optionalDecoded(intent, key);
        if(value.trim().isEmpty()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static String optionalDecoded(Intent intent, String key) {
        String encoded = intent.getStringExtra(key);
        if(encoded == null || encoded.trim().isEmpty()) return "";
        try {
            return new String(
                    Base64.decode(encoded.trim(), Base64.URL_SAFE | Base64.NO_WRAP),
                    StandardCharsets.UTF_8);
        } catch(IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid " + key, failure);
        }
    }

    private static String encode(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int stablePositiveId(String identity) {
        try {
            long numeric = Long.parseLong(identity);
            if(numeric > 0L && numeric <= Integer.MAX_VALUE) return (int)numeric;
        } catch(NumberFormatException ignored) {
        }
        int hashed = identity.hashCode() & Integer.MAX_VALUE;
        return hashed == 0 ? 1 : hashed;
    }
}
