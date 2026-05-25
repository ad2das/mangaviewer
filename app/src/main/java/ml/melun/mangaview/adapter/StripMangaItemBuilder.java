package ml.melun.mangaview.adapter;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

final class StripMangaItemBuilder {
    interface PageSeeder {
        void seed(PageItem page);
    }

    private StripMangaItemBuilder() {
    }

    static ArrayList<Object> appendItems(Manga manga, List<String> images, boolean autoCut,
                                         boolean includeLeadingInfo, PageSeeder seeder) {
        ArrayList<Object> items = new ArrayList<>(capacity(images, autoCut) + (includeLeadingInfo ? 2 : 1));
        if(includeLeadingInfo)
            items.add(new InfoItem(manga.prevEp(), manga));
        addPages(items, manga, images, autoCut, seeder);
        items.add(new InfoItem(manga, manga.nextEp()));
        return items;
    }

    static ArrayList<Object> prependItems(Manga manga, List<String> images, boolean autoCut,
                                          PageSeeder seeder) {
        ArrayList<Object> items = new ArrayList<>(capacity(images, autoCut) + 1);
        items.add(new InfoItem(null, manga));
        addPages(items, manga, images, autoCut, seeder);
        return items;
    }

    private static void addPages(List<Object> items, Manga manga, List<String> images,
                                 boolean autoCut, PageSeeder seeder) {
        if(images == null)
            return;
        for(int i = 0; i < images.size(); i++) {
            PageItem first = new PageItem(i, images.get(i), manga);
            seed(seeder, first);
            items.add(first);
            if(autoCut) {
                PageItem second = new PageItem(i, images.get(i), manga, PageItem.SECOND);
                seed(seeder, second);
                items.add(second);
            }
        }
    }

    private static void seed(PageSeeder seeder, PageItem page) {
        if(seeder != null)
            seeder.seed(page);
    }

    private static int capacity(List<String> images, boolean autoCut) {
        return images == null ? 0 : images.size() * (autoCut ? 2 : 1);
    }
}
