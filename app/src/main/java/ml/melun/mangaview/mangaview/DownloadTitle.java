package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.List;

public class DownloadTitle extends MTitle {
    private List<Manga> eps;

    public DownloadTitle(Title t){
        super(t.getName(), t.getId(), t.getThumb(), t.getAuthor(), t.getTags(), t.getRelease(), t.getBaseMode());
        eps = new ArrayList<>();
        List<Manga> source;
        try {
            List<Manga> titleEpisodes = t.getEps();
            source = titleEpisodes == null ? null : new ArrayList<>(titleEpisodes);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            source = null;
        }
        if(source == null)
            return;
        for(Manga episode : source) {
            if(episode == null)
                continue;
            Manga copy = new Manga(episode.getId(), episode.getName(), episode.getDate(), episode.getBaseMode());
            copy.addThumb(episode.getThumb());
            copy.setMode(0);
            copy.setTitleId(t.getId());
            copy.setNtkEpisodePath(episode.getNtkEpisodePath());
            copy.setNtkImageEpisodeId(episode.getNtkImageEpisodeId());
            copy.setNtkImageCount(episode.getNtkImageCount());
            eps.add(copy);
        }
    }

    public List<Manga> getEps() {
        return eps;
    }

    public void setEps(List<Manga> eps) {
        this.eps = eps;
    }
}
