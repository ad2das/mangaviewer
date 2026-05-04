package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.List;

public class DownloadTitle extends MTitle {
    private List<Manga> eps;

    public DownloadTitle(Title t){
        super(t.getName(), t.getId(), t.getThumb(), t.getAuthor(), t.getTags(), t.getRelease(), t.getBaseMode());
        eps = new ArrayList<>();
        if(t.getEps() == null)
            return;
        for(Manga episode : t.getEps()) {
            if(episode == null)
                continue;
            Manga copy = new Manga(episode.getId(), episode.getName(), episode.getDate(), episode.getBaseMode());
            copy.addThumb(episode.getThumb());
            copy.setMode(0);
            copy.setTitleId(t.getId());
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
