package ml.melun.mangaview.adapter;

import java.util.List;

import ml.melun.mangaview.mangaview.Title;

final class HomeTitleSelector {
    private HomeTitleSelector() {
    }

    static Title firstValidTitle(List<Title> titles) {
        if(titles == null)
            return null;
        for(Title title : titles) {
            if(title != null && title.getId() > 0)
                return title;
        }
        return null;
    }

    static Title firstValidTitleForTest(List<Title> titles) {
        return firstValidTitle(titles);
    }
}
