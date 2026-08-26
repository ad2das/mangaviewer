package ml.melun.mangaview.mangaview;

/**
 * Allocation-light parser for the visible episode numbers embedded in a title.
 *
 * <p>Android's {@code java.util.regex} implementation creates an ICU native matcher for every
 * match. Episode adjacency evaluates the same immutable titles many times, so regex matching here
 * used to register thousands of short-lived native allocations and repeatedly force ART's
 * process-wide NativeAlloc GC. This scanner accepts the same decimal, comma, range and part-number
 * grammar without crossing a plain whitespace boundary between unrelated numbers.</p>
 */
final class EpisodeNumberParser {
    static final Result EMPTY = new Result("", -1.0d, -1.0d, -1.0d);

    private static final String[] SPECIAL_MARKERS = {
            "번외", "외전", "특별", "부록", "기록", "후기", "프롤로그"
    };

    private EpisodeNumberParser() {
    }

    static Result parse(String title) {
        if(title == null || title.length() == 0 || containsSpecialMarker(title))
            return EMPTY;
        double orderingValue = -1.0d;
        Block last = null;
        for(int index = 0; index < title.length(); index++) {
            if(title.charAt(index) != '화')
                continue;
            Block block = parseBlockEndingAt(title, index);
            if(block == null)
                continue;
            orderingValue = Math.max(orderingValue, block.orderingValue);
            last = block;
        }
        if(last == null)
            return EMPTY;
        return new Result(last.key, last.min, last.max, orderingValue);
    }

    static String removeWhitespace(String value) {
        if(value == null || value.length() == 0)
            return "";
        int firstWhitespace = -1;
        for(int index = 0; index < value.length(); index++) {
            if(Character.isWhitespace(value.charAt(index))) {
                firstWhitespace = index;
                break;
            }
        }
        if(firstWhitespace < 0)
            return value;
        StringBuilder compact = new StringBuilder(value.length());
        compact.append(value, 0, firstWhitespace);
        for(int index = firstWhitespace + 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if(!Character.isWhitespace(current))
                compact.append(current);
        }
        return compact.toString();
    }

    static String cleanViewerPrefix(String value) {
        if(value == null)
            return "";
        int length = value.length();
        int start = 0;
        while(start < length && value.charAt(start) <= ' ')
            start++;
        int end = length;
        while(end > start && value.charAt(end - 1) <= ' ')
            end--;
        int cursor = start;
        if(cursor >= end || value.charAt(cursor) != '(')
            return sliceIfNeeded(value, start, end);
        cursor++;
        cursor = skipWhitespace(value, cursor, end);
        int firstEnd = skipDigits(value, cursor, end);
        if(firstEnd == cursor)
            return sliceIfNeeded(value, start, end);
        cursor = skipWhitespace(value, firstEnd, end);
        if(cursor >= end || value.charAt(cursor) != '/')
            return sliceIfNeeded(value, start, end);
        cursor = skipWhitespace(value, cursor + 1, end);
        int secondEnd = skipDigits(value, cursor, end);
        if(secondEnd == cursor)
            return sliceIfNeeded(value, start, end);
        cursor = skipWhitespace(value, secondEnd, end);
        if(cursor >= end || value.charAt(cursor) != ')')
            return sliceIfNeeded(value, start, end);
        cursor = skipWhitespace(value, cursor + 1, end);
        return sliceIfNeeded(value, cursor, end);
    }

    private static Block parseBlockEndingAt(String title, int episodeSuffixIndex) {
        int cursor = episodeSuffixIndex;
        while(cursor > 0 && Character.isWhitespace(title.charAt(cursor - 1)))
            cursor--;
        int lastNumberEnd = cursor;
        int start = numberStartBackward(title, cursor);
        if(start < 0)
            return null;
        while(true) {
            int separatorCursor = start;
            while(separatorCursor > 0 && Character.isWhitespace(title.charAt(separatorCursor - 1)))
                separatorCursor--;
            if(separatorCursor <= 0 || !isSeparator(title.charAt(separatorCursor - 1)))
                break;
            int previousEnd = separatorCursor - 1;
            while(previousEnd > 0 && Character.isWhitespace(title.charAt(previousEnd - 1)))
                previousEnd--;
            int previousStart = numberStartBackward(title, previousEnd);
            if(previousStart < 0)
                break;
            start = previousStart;
        }
        return parseForwardBlock(title, start, lastNumberEnd);
    }

    private static Block parseForwardBlock(String value, int start, int end) {
        StringBuilder key = new StringBuilder(Math.max(8, end - start));
        int tokenCount = 0;
        int delimiterPosition = -1;
        char onlyDelimiter = 0;
        boolean allIntegers = true;
        double first = -1.0d;
        double second = -1.0d;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int cursor = start;
        while(cursor < end) {
            cursor = skipWhitespace(value, cursor, end);
            int tokenStart = cursor;
            while(cursor < end && isDigit(value.charAt(cursor)))
                cursor++;
            int integerEnd = cursor;
            int decimalStart = -1;
            int decimalEnd = -1;
            if(cursor < end && value.charAt(cursor) == '.' &&
                    cursor + 1 < end && isDigit(value.charAt(cursor + 1))) {
                decimalStart = ++cursor;
                while(cursor < end && isDigit(value.charAt(cursor)))
                    cursor++;
                decimalEnd = cursor;
                allIntegers = false;
            }
            if(integerEnd == tokenStart)
                return null;
            double number = numericValue(value, tokenStart, integerEnd, decimalStart, decimalEnd);
            if(tokenCount == 0)
                first = number;
            else if(tokenCount == 1)
                second = number;
            if(tokenCount > 0) {
                delimiterPosition = key.length();
                key.append(',');
            }
            appendNormalizedNumber(key, value, tokenStart, integerEnd, decimalStart, decimalEnd);
            tokenCount++;
            min = Math.min(min, number);
            max = Math.max(max, number);

            cursor = skipWhitespace(value, cursor, end);
            if(cursor >= end)
                break;
            char delimiter = value.charAt(cursor);
            if(!isSeparator(delimiter))
                return null;
            if(tokenCount == 1)
                onlyDelimiter = delimiter;
            else
                onlyDelimiter = 0;
            cursor++;
        }
        if(tokenCount == 0)
            return null;
        boolean hyphenPart = tokenCount == 2 && onlyDelimiter == '-' && allIntegers &&
                first > 0.0d && second > 0.0d && second < first;
        if(hyphenPart) {
            key.setCharAt(delimiterPosition, '-');
            double partValue = first + Math.min(second, 9999.0d) / 10000.0d;
            return new Block(key.toString(), partValue, partValue, partValue);
        }
        return new Block(key.toString(), min, max, max);
    }

    private static int numberStartBackward(String value, int end) {
        int cursor = end;
        int digitEnd = cursor;
        while(cursor > 0 && isDigit(value.charAt(cursor - 1)))
            cursor--;
        if(cursor == digitEnd)
            return -1;
        if(cursor > 1 && value.charAt(cursor - 1) == '.' && isDigit(value.charAt(cursor - 2))) {
            cursor--;
            while(cursor > 0 && isDigit(value.charAt(cursor - 1)))
                cursor--;
        }
        return cursor;
    }

    private static double numericValue(
            String value,
            int integerStart,
            int integerEnd,
            int decimalStart,
            int decimalEnd
    ) {
        double result = 0.0d;
        for(int index = integerStart; index < integerEnd; index++)
            result = result * 10.0d + (value.charAt(index) - '0');
        if(decimalStart >= 0) {
            double scale = 0.1d;
            for(int index = decimalStart; index < decimalEnd; index++) {
                result += (value.charAt(index) - '0') * scale;
                scale *= 0.1d;
            }
        }
        return result;
    }

    private static void appendNormalizedNumber(
            StringBuilder out,
            String value,
            int integerStart,
            int integerEnd,
            int decimalStart,
            int decimalEnd
    ) {
        int normalizedIntegerStart = integerStart;
        while(normalizedIntegerStart + 1 < integerEnd && value.charAt(normalizedIntegerStart) == '0')
            normalizedIntegerStart++;
        out.append(value, normalizedIntegerStart, integerEnd);
        if(decimalStart < 0)
            return;
        int normalizedDecimalEnd = decimalEnd;
        while(normalizedDecimalEnd > decimalStart && value.charAt(normalizedDecimalEnd - 1) == '0')
            normalizedDecimalEnd--;
        if(normalizedDecimalEnd > decimalStart) {
            out.append('.');
            out.append(value, decimalStart, normalizedDecimalEnd);
        }
    }

    private static boolean containsSpecialMarker(String value) {
        for(String marker : SPECIAL_MARKERS) {
            if(containsIgnoringWhitespace(value, marker))
                return true;
        }
        return false;
    }

    private static boolean containsIgnoringWhitespace(String value, String target) {
        for(int start = 0; start < value.length(); start++) {
            int source = start;
            int expected = 0;
            while(source < value.length() && expected < target.length()) {
                char current = value.charAt(source++);
                if(Character.isWhitespace(current))
                    continue;
                if(current != target.charAt(expected))
                    break;
                expected++;
            }
            if(expected == target.length())
                return true;
        }
        return false;
    }

    private static int skipWhitespace(String value, int start, int end) {
        int cursor = start;
        while(cursor < end && Character.isWhitespace(value.charAt(cursor)))
            cursor++;
        return cursor;
    }

    private static int skipDigits(String value, int start, int end) {
        int cursor = start;
        while(cursor < end && isDigit(value.charAt(cursor)))
            cursor++;
        return cursor;
    }

    private static String sliceIfNeeded(String value, int start, int end) {
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static boolean isSeparator(char value) {
        return value == ',' || value == '~' || value == '～' || value == '-';
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    static final class Result {
        final String key;
        final double min;
        final double max;
        final double orderingValue;

        Result(String key, double min, double max, double orderingValue) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.orderingValue = orderingValue;
        }

        boolean isValid() {
            return key.length() > 0;
        }
    }

    private static final class Block {
        final String key;
        final double min;
        final double max;
        final double orderingValue;

        Block(String key, double min, double max, double orderingValue) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.orderingValue = orderingValue;
        }
    }
}
