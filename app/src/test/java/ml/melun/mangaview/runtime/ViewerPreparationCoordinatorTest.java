package ml.melun.mangaview.runtime;

import org.junit.Test;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Title;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ViewerPreparationCoordinatorTest {
    @Test
    public void pendingFirstFrameIsNotLaunchable() {
        PreparedViewerLaunch launch = ViewerPreparationCoordinator.statusForResult(
                ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);

        assertEquals(PreparedViewerLaunch.Status.FIRST_FRAME_PENDING, launch.getStatus());
        assertFalse(launch.canLaunch());
    }

    @Test
    public void preparationWaitIsLongerForNormalModeThanDataSave() {
        assertEquals(1800L, ViewerPreparationCoordinator.continueClickWaitMs(false));
        assertEquals(1200L, ViewerPreparationCoordinator.continueClickWaitMs(true));
        assertEquals(2500L, ViewerPreparationCoordinator.postPrepareWaitMs(false));
        assertEquals(1800L, ViewerPreparationCoordinator.postPrepareWaitMs(true));
    }

    @Test
    public void captchaResultKeepsCaptchaStatus() {
        PreparedViewerLaunch launch = ViewerPreparationCoordinator.statusForResult(Title.LOAD_CAPTCHA);

        assertEquals(PreparedViewerLaunch.Status.CAPTCHA, launch.getStatus());
        assertFalse(launch.canLaunch());
    }
}
