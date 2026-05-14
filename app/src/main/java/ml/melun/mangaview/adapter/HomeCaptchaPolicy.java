package ml.melun.mangaview.adapter;

final class HomeCaptchaPolicy {
    private HomeCaptchaPolicy() {
    }

    static boolean shouldOpenCaptchaOnEmptyFetch(boolean ntk, boolean hasDisplayContent) {
        return ntk || !hasDisplayContent;
    }
}
