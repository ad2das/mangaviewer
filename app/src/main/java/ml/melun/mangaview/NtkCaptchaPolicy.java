package ml.melun.mangaview;

final class NtkCaptchaPolicy {
    private NtkCaptchaPolicy() {
    }

    static boolean isAccessProbeChallenged(boolean responseReceived, int code, String body, boolean cloudflareChallenge) {
        if(!responseReceived)
            return true;
        if(cloudflareChallenge)
            return true;
        return code == 403 && body != null && body.length() > 0;
    }
}
