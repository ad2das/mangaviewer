package ml.melun.mangaview.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.CustomHttpClient;

@RunWith(AndroidJUnit4.class)
public class NtkCaptchaLiveInstrumentedTest {
    private static final String NTK_ROOT = CustomHttpClient.NTK_WEBTOON_URL;

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void captchaActivityLoadsRealNtkChallengeOrNormalPage() throws Exception {
        Intent intent = prepareNtkCaptchaIntent();

        try(ActivityScenario<CaptchaActivity> scenario = ActivityScenario.launch(intent)) {
            PageState last = new PageState();
            long deadline = System.currentTimeMillis() + 70000L;
            boolean loadedChallengeOrNormal = false;
            while(System.currentTimeMillis() < deadline) {
                if(MainApplication.getHttpClient().hasCloudflareClearance()) {
                    loadedChallengeOrNormal = true;
                    break;
                }
                PageState state = readPageState(scenario);
                if(state != null)
                    last = state;
                if(last.looksLikeChallengeOrNormal()) {
                    loadedChallengeOrNormal = true;
                    break;
                }
                if(last.looksLikeWebViewNetworkError()
                        && System.currentTimeMillis() > deadline - 20000L)
                    break;
                Thread.sleep(1000L);
            }

            assertFalse("NTK captcha WebView must not stay on a network error page: " + last.summary(),
                    last.looksLikeWebViewNetworkError());
            assertTrue("Expected real NTK captcha challenge or normal page, got: " + last.summary(),
                    loadedChallengeOrNormal);
        }
    }

    @Test
    public void captchaActivityReceivesRealNtkClearanceWhenRequested() throws Exception {
        String wait = InstrumentationRegistry.getArguments().getString("waitForNtkClearance");
        Assume.assumeTrue("Manual NTK clearance smoke requires -e waitForNtkClearance true",
                "true".equalsIgnoreCase(wait));

        Intent intent = prepareNtkCaptchaIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        CaptchaActivity activity = null;
        try {
            activity = (CaptchaActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
            PageState last = new PageState();
            long deadline = System.currentTimeMillis() + 180000L;
            while(System.currentTimeMillis() < deadline) {
                if(MainApplication.getHttpClient().hasCloudflareClearance())
                    break;
                PageState state = readPageState(activity);
                if(state != null)
                    last = state;
                Thread.sleep(1000L);
            }

            assertTrue("Expected cf_clearance to sync into app HTTP client, got: " + last.summary(),
                    MainApplication.getHttpClient().hasCloudflareClearance());

            long closeDeadline = System.currentTimeMillis() + 30000L;
            while(System.currentTimeMillis() < closeDeadline
                    && activity != null
                    && !activity.isDestroyed()) {
                Thread.sleep(500L);
            }
            assertTrue("Expected CaptchaActivity to close after verified NTK clearance",
                    activity == null || activity.isDestroyed());
        } finally {
            if(activity != null && !activity.isDestroyed()) {
                CaptchaActivity toFinish = activity;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(toFinish::finish);
            }
        }
    }

    private Intent prepareNtkCaptchaIntent() {
        Context context = ApplicationProvider.getApplicationContext();
        String siteRoot = InstrumentationRegistry.getArguments().getString("ntkSiteRoot", NTK_ROOT);
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit().clear().commit();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().resetCookie();

        Intent intent = new Intent(context, CaptchaActivity.class);
        intent.putExtra("url", siteRoot + "/");
        return intent;
    }

    private PageState readPageState(ActivityScenario<CaptchaActivity> scenario) throws Exception {
        AtomicReference<PageState> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            if(activity.webView == null) {
                latch.countDown();
                return;
            }
            activity.webView.evaluateJavascript(
                    "(function(){"
                            + "var html=document.documentElement?document.documentElement.outerHTML:'';"
                            + "var text=document.body?(document.body.innerText||''):'';"
                            + "var cookie='';try{cookie=document.cookie||'';}catch(e){}"
                            + "var hasFrame=!!document.querySelector('iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]');"
                            + "var hasElement=!!document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"],[id*=\"turnstile\"]');"
                            + "var hasShadow=false;try{var all=document.querySelectorAll('*');for(var i=0;i<all.length;i++){var sr=all[i].shadowRoot||all[i].__sr;if(sr&&(sr.querySelector('input[type=\"checkbox\"],iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"],.cf-turnstile,.turnstile'))){hasShadow=true;break;}}}catch(e){}"
                            + "var visible=/verify you are human|performing security verification|checking your browser|just a moment/i.test(text);"
                            + "var normal=!!document.querySelector('a[href^=\"/manhwa\"],a[href^=\"/webtoon\"],a[href*=\"/manhwa/\"],a[href*=\"/webtoon/\"],img[src*=\"/webtoon_uploads/\"],img[data-src*=\"/webtoon_uploads/\"],img[src*=\"/manhwa_uploads/\"],img[data-src*=\"/manhwa_uploads/\"]');"
                            + "var links=document.querySelectorAll('a[href]').length;var imgs=document.querySelectorAll('img[src],img[data-src]').length;if(links>=8||imgs>=4)normal=true;"
                            + "return JSON.stringify({url:location.href,title:document.title||'',text:text.slice(0,2000),html:html.slice(0,4000),cookie:cookie,hasChallengeFrame:hasFrame,hasChallengeElement:hasElement,hasShadowChallenge:hasShadow,hasVisibleChallengeText:visible,hasNormalPage:normal});"
                            + "})()",
                    value -> {
                        out.set(PageState.fromJavascript(value));
                        latch.countDown();
                    });
        });
        latch.await(5, TimeUnit.SECONDS);
        return out.get();
    }

    private PageState readPageState(CaptchaActivity activity) throws Exception {
        AtomicReference<PageState> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if(activity.webView == null) {
                latch.countDown();
                return;
            }
            activity.webView.evaluateJavascript(
                    "(function(){"
                            + "var html=document.documentElement?document.documentElement.outerHTML:'';"
                            + "var text=document.body?(document.body.innerText||''):'';"
                            + "var cookie='';try{cookie=document.cookie||'';}catch(e){}"
                            + "var hasFrame=!!document.querySelector('iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"]');"
                            + "var hasElement=!!document.querySelector('.cf-turnstile,.turnstile,[class*=\"turnstile\"],[id*=\"turnstile\"]');"
                            + "var hasShadow=false;try{var all=document.querySelectorAll('*');for(var i=0;i<all.length;i++){var sr=all[i].shadowRoot||all[i].__sr;if(sr&&(sr.querySelector('input[type=\"checkbox\"],iframe[src*=\"turnstile\"],iframe[src*=\"challenges.cloudflare\"],.cf-turnstile,.turnstile'))){hasShadow=true;break;}}}catch(e){}"
                            + "var visible=/verify you are human|performing security verification|checking your browser|just a moment/i.test(text);"
                            + "var normal=!!document.querySelector('a[href^=\"/manhwa\"],a[href^=\"/webtoon\"],a[href*=\"/manhwa/\"],a[href*=\"/webtoon/\"],img[src*=\"/webtoon_uploads/\"],img[data-src*=\"/webtoon_uploads/\"],img[src*=\"/manhwa_uploads/\"],img[data-src*=\"/manhwa_uploads/\"]');"
                            + "var links=document.querySelectorAll('a[href]').length;var imgs=document.querySelectorAll('img[src],img[data-src]').length;if(links>=8||imgs>=4)normal=true;"
                            + "return JSON.stringify({url:location.href,title:document.title||'',text:text.slice(0,2000),html:html.slice(0,4000),cookie:cookie,hasChallengeFrame:hasFrame,hasChallengeElement:hasElement,hasShadowChallenge:hasShadow,hasVisibleChallengeText:visible,hasNormalPage:normal});"
                            + "})()",
                    value -> {
                        out.set(PageState.fromJavascript(value));
                        latch.countDown();
                    });
        });
        latch.await(5, TimeUnit.SECONDS);
        return out.get();
    }

    private static final class PageState {
        final String url;
        final String title;
        final String text;
        final String html;
        final String cookie;
        final boolean hasChallengeFrame;
        final boolean hasChallengeElement;
        final boolean hasShadowChallenge;
        final boolean hasVisibleChallengeText;
        final boolean hasNormalPage;

        PageState() {
            this("", "", "", "", "", false, false, false, false, false);
        }

        PageState(String url, String title, String text, String html, String cookie,
                  boolean hasChallengeFrame, boolean hasChallengeElement,
                  boolean hasShadowChallenge, boolean hasVisibleChallengeText,
                  boolean hasNormalPage) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.html = html == null ? "" : html;
            this.cookie = cookie == null ? "" : cookie;
            this.hasChallengeFrame = hasChallengeFrame;
            this.hasChallengeElement = hasChallengeElement;
            this.hasShadowChallenge = hasShadowChallenge;
            this.hasVisibleChallengeText = hasVisibleChallengeText;
            this.hasNormalPage = hasNormalPage;
        }

        static PageState fromJavascript(String value) {
            try {
                Object decoded = new JSONTokener(value).nextValue();
                String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                JSONObject object = new JSONObject(json);
                return new PageState(
                        object.optString("url"),
                        object.optString("title"),
                        object.optString("text"),
                        object.optString("html"),
                        object.optString("cookie"),
                        object.optBoolean("hasChallengeFrame"),
                        object.optBoolean("hasChallengeElement"),
                        object.optBoolean("hasShadowChallenge"),
                        object.optBoolean("hasVisibleChallengeText"),
                        object.optBoolean("hasNormalPage"));
            } catch (Exception e) {
                return new PageState("", "", value, value, "", false, false, false, false, false);
            }
        }

        boolean looksLikeChallengeOrNormal() {
            String lower = combinedLower();
            return hasChallengeFrame
                    || hasShadowChallenge
                    || hasNormalPage
                    || lower.contains("cf_clearance")
                    || lower.contains("/manhwa/")
                    || lower.contains("/webtoon/")
                    || lower.contains("newtoki");
        }

        boolean looksLikeWebViewNetworkError() {
            String lower = combinedLower();
            return lower.contains("webpage not available")
                    || lower.contains("net::err_")
                    || lower.contains("err_connection_reset")
                    || lower.contains("err_timed_out")
                    || lower.contains("err_name_not_resolved");
        }

        String summary() {
            String combined = (title + " " + url + " " + text).replaceAll("\\s+", " ").trim();
            String clipped = combined.length() > 240 ? combined.substring(0, 240) : combined;
            return "frame=" + hasChallengeFrame
                    + ",element=" + hasChallengeElement
                    + ",shadow=" + hasShadowChallenge
                    + ",visibleText=" + hasVisibleChallengeText
                    + ",normal=" + hasNormalPage
                    + " " + clipped;
        }

        private String combinedLower() {
            return (url + "\n" + title + "\n" + text + "\n" + html + "\n" + cookie).toLowerCase(java.util.Locale.ROOT);
        }
    }
}
