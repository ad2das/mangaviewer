package ml.melun.mangaview.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGestureRoutingTest {
    @Test
    fun `vertical movement owns gesture as soon as it crosses touch slop`() {
        val lock = VerticalGestureAxisLock(touchSlop = 8f)
        lock.begin(100f, 200f)

        assertEquals(VerticalGestureAxisLock.Route.PENDING, lock.classify(106f, 207f))
        assertEquals(VerticalGestureAxisLock.Route.FORWARD, lock.classify(107f, 209f))
        assertEquals(VerticalGestureAxisLock.Route.FORWARD, lock.classify(300f, 201f))
    }

    @Test
    fun `horizontal movement never turns into a control click`() {
        val lock = VerticalGestureAxisLock(touchSlop = 8f)
        lock.begin(100f, 200f)

        assertEquals(VerticalGestureAxisLock.Route.REJECT, lock.classify(110f, 205f))
        assertEquals(VerticalGestureAxisLock.Route.REJECT, lock.classify(101f, 240f))
        assertEquals(VerticalGestureAxisLock.Route.REJECT, lock.currentRoute)
    }

    @Test
    fun `tap remains pending through inclusive touch slop`() {
        val lock = VerticalGestureAxisLock(touchSlop = 8f)
        lock.begin(100f, 200f)

        assertEquals(VerticalGestureAxisLock.Route.PENDING, lock.classify(108f, 208f))
    }

    @Test
    fun `surface tap fires exactly once and movement or extra pointer cancels it`() {
        val tracker = SurfaceTapTracker(touchSlop = 8f)
        tracker.begin(10f, 10f, eligible = true)
        assertTrue(tracker.release(14f, 15f))
        assertFalse(tracker.release(14f, 15f))

        tracker.begin(10f, 10f, eligible = true)
        tracker.move(10f, 19f)
        assertFalse(tracker.release(10f, 10f))

        tracker.begin(10f, 10f, eligible = true)
        tracker.cancel()
        assertFalse(tracker.release(10f, 10f))
    }

    @Test
    fun `chrome hit never becomes a surface tap`() {
        val tracker = SurfaceTapTracker(touchSlop = 8f)
        tracker.begin(10f, 10f, eligible = false)

        assertFalse(tracker.release(10f, 10f))
    }
}
