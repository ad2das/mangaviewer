package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderSessionPreparedRunwayPolicyTest {
    @Test
    public void stagingKeepsEveryPostStorePageDecodeCold() {
        int preparedLast = 7;

        assertTrue(ReaderSession.preparedRunwayDecodeColdForTest(
                8, preparedLast, false, false, false));
        assertTrue(ReaderSession.preparedRunwayDecodeColdForTest(
                11, preparedLast, false, false, false));
        assertFalse(ReaderSession.preparedRunwayDecodeColdForTest(
                7, preparedLast, false, false, false));
    }

    @Test
    public void activationReleasesTwelvePageFullQualityContinuation() {
        int preparedLast = 7;

        assertFalse(ReaderSession.preparedRunwayDecodeColdForTest(
                8, preparedLast, true, false, false));
        assertFalse(ReaderSession.preparedRunwayDecodeColdForTest(
                19, preparedLast, true, false, false));
        assertTrue(ReaderSession.preparedRunwayDecodeColdForTest(
                20, preparedLast, true, false, false));
    }

    @Test
    public void continuationStartsAfterLatestContiguousStoreWatermark() {
        assertEquals(8, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 7, 31)[0]);
        assertEquals(19, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 7, 31)[1]);

        assertEquals(4, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 3, 31)[0]);
        assertEquals(15, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 3, 31)[1]);

        assertEquals(30, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 29, 31)[0]);
        assertEquals(30, ReaderSession.preparedPostActivationContinuationBoundsForTest(
                3, 29, 31)[1]);
    }

    @Test
    public void realInputStillReleasesFarTailWithoutChangingPreparedPrefix() {
        int preparedLast = 7;

        assertFalse(ReaderSession.preparedRunwayDecodeColdForTest(
                12, preparedLast, false, true, false));
        assertFalse(ReaderSession.preparedRunwayDecodeColdForTest(
                12, preparedLast, false, false, true));
    }

    @Test
    public void physicalRunwayDeliversOriginalTilesWithoutWaitingForInputOrInputQuiet() {
        assertTrue(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, true, true, false, 8, 5, 9));
        assertTrue(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, false, true, false, 8, 5, 9));
        assertFalse(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, true, true, false, 10, 5, 9));
        assertFalse(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, true, false, false, 8, 5, 9));
        assertTrue(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, true, true, true, 8, 5, 9));
        assertFalse(ReaderSession.strictInlinePhysicalTileDeliveryForTest(
                true, true, true, true, 10, 5, 9));
    }

    @Test
    public void authoritativeInstallRetryIsBoundedInsteadOfSpinning() {
        assertTrue(ReaderSession.strictInlineInstallRetryDelayForTest(1) > 0L);
        assertTrue(ReaderSession.strictInlineInstallRetryDelayForTest(2) > 0L);
        assertTrue(ReaderSession.strictInlineInstallRetryDelayForTest(3) < 0L);
    }

    @Test
    public void strictOriginalRegionTilesUseSurfaceContractHeight() {
        assertEquals(2048, ReaderSession.decodeTileSourceHeightForTest(true));
        assertEquals(1024, ReaderSession.decodeTileSourceHeightForTest(false));
    }

    @Test
    public void strictInlineProofUsesTheSameOriginalTileIdentityAndJoinsPendingWinner() {
        assertTrue(ReaderSession.strictInlineForceOriginalTilesForTest(true, true));
        assertTrue(ReaderSession.strictInlineForceOriginalTilesForTest(true, false));
        assertFalse(ReaderSession.strictInlineForceOriginalTilesForTest(false, true));

        assertTrue(ReaderSession.strictInlineProofJoinsPendingOriginalForTest(
                true, true, 764));
        assertFalse(ReaderSession.strictInlineProofJoinsPendingOriginalForTest(
                true, false, 764));
        assertFalse(ReaderSession.strictInlineProofJoinsPendingOriginalForTest(
                false, true, 764));
        assertFalse(ReaderSession.strictInlineProofJoinsPendingOriginalForTest(
                true, true, 0));
    }
}
