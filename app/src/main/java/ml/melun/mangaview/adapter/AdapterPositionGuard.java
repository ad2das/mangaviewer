package ml.melun.mangaview.adapter;

import java.util.List;

final class AdapterPositionGuard {
    private AdapterPositionGuard() {
    }

    static boolean isValidPosition(List<?> list, int position) {
        return list != null && position >= 0 && position < list.size();
    }

    static boolean isValidPositionForTest(List<?> list, int position) {
        return isValidPosition(list, position);
    }
}
