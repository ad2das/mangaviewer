package ml.melun.mangaview.activity;

final class CaptchaCookiePolicy {
    interface CookiePairConsumer {
        void accept(String key, String value);
    }

    private CaptchaCookiePolicy() {
    }

    static String extractCookieValue(String text, String cookieName) {
        if(text == null || cookieName == null || cookieName.length() == 0)
            return null;
        final String[] value = new String[1];
        forEachCookiePair(text, (key, cookieValue) -> {
            if(value[0] == null && cookieName.equalsIgnoreCase(key))
                value[0] = cookieValue;
        });
        return value[0];
    }

    static void forEachCookiePair(String cookieStr, CookiePairConsumer consumer) {
        if(cookieStr == null || consumer == null)
            return;
        int start = 0;
        int length = cookieStr.length();
        while(start < length) {
            int end = nextCookieSeparator(cookieStr, start);
            int eq = cookieStr.indexOf('=', start);
            if(eq > start && eq < end) {
                String key = cookieStr.substring(start, eq).trim();
                String value = cookieStr.substring(eq + 1, end).trim();
                if(key.length() > 0)
                    consumer.accept(key, value);
            }
            start = end + 1;
        }
    }

    static int nextCookieSeparator(String cookieStr, int start) {
        int end = cookieStr.length();
        int semicolon = cookieStr.indexOf(';', start);
        if(semicolon >= 0 && semicolon < end)
            end = semicolon;
        int newline = cookieStr.indexOf('\n', start);
        if(newline >= 0 && newline < end)
            end = newline;
        int carriageReturn = cookieStr.indexOf('\r', start);
        if(carriageReturn >= 0 && carriageReturn < end)
            end = carriageReturn;
        return end;
    }

    static boolean isValidClearanceValue(String value) {
        if(value == null)
            return false;
        String trimmed = value.trim();
        return trimmed.length() >= 20
                && !"deleted".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed);
    }
}

