package ml.melun.mangaview.adapter;

final class HomeCaptchaPolicy {
    private HomeCaptchaPolicy() {
    }

    static boolean shouldOpenCaptchaOnEmptyFetch(boolean ntk, boolean hasDisplayContent, boolean cloudflareChallenge) {
        if(cloudflareChallenge)
            return true;
        return !ntk && !hasDisplayContent;
    }
}
