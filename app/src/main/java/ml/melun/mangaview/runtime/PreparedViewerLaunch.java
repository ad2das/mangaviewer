package ml.melun.mangaview.runtime;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public final class PreparedViewerLaunch {
    public enum Status {
        READY,
        OFFLINE,
        CAPTCHA,
        EMPTY_IMAGES,
        FIRST_FRAME_PENDING,
        PATHLESS_NTK,
        CANCELLED,
        ERROR
    }

    private final Status status;
    private final Manga manga;
    private final Title title;
    private final int resultCode;

    private PreparedViewerLaunch(Status status, Manga manga, Title title, int resultCode) {
        this.status = status == null ? Status.ERROR : status;
        this.manga = manga;
        this.title = title;
        this.resultCode = resultCode;
    }

    public static PreparedViewerLaunch ready(Manga manga, Title title) {
        return new PreparedViewerLaunch(Status.READY, manga, title, Title.LOAD_OK);
    }

    public static PreparedViewerLaunch offline(Manga manga, Title title) {
        return new PreparedViewerLaunch(Status.OFFLINE, manga, title, Title.LOAD_OK);
    }

    public static PreparedViewerLaunch failed(Status status, int resultCode) {
        return new PreparedViewerLaunch(status, null, null, resultCode);
    }

    public boolean canLaunch() {
        return (status == Status.READY || status == Status.OFFLINE) && manga != null;
    }

    public boolean isCaptcha() {
        return status == Status.CAPTCHA;
    }

    public Status getStatus() {
        return status;
    }

    public Manga getManga() {
        return manga;
    }

    public Title getTitle() {
        return title;
    }

    public int getResultCode() {
        return resultCode;
    }
}
