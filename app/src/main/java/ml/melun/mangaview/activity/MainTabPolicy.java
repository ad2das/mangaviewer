package ml.melun.mangaview.activity;

import ml.melun.mangaview.R;

final class MainTabPolicy {
    private MainTabPolicy() {
    }

    static int tabId(int index) {
        switch(index) {
            case 0:
                return R.id.nav_main;
            case 1:
                return R.id.nav_search;
            case 2:
                return R.id.nav_recent;
        }
        return 0;
    }

    static int fragmentIndex(int itemId) {
        switch(itemId) {
            case R.id.nav_main:
                return 0;
            case R.id.nav_search:
                return 1;
            case R.id.nav_recent:
            case R.id.nav_favorite:
            case R.id.nav_download:
                return 2;
        }
        return -1;
    }

    static CharSequence tabTitle(int index) {
        switch(index) {
            case 0:
                return "MangaView";
            case 1:
                return "검색";
            case 2:
                return "내 보관함";
        }
        return "";
    }
}

