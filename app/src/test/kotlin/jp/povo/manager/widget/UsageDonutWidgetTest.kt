package jp.povo.manager.widget

import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks the ring widget's readout and, more importantly, its sizing.
 *
 * The sizing is the part that can silently be wrong: the widget defaults to a
 * single 1x1 cell, which on a stock grid is only about 73dp x 113dp, and
 * Glance cannot measure text — so if the arithmetic in [Metrics] drifts, the
 * result is a clipped timestamp or a readout spilling over the ring, neither of
 * which any compiler catches.
 */
@RunWith(RobolectricTestRunner::class)
// See UsageWidgetTest for why the SDK and Application are pinned.
@Config(sdk = [36], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageDonutWidgetTest {

    @Test
    fun `readout and timestamp reach the screen`() = runGlanceAppWidgetUnitTest {
        val metrics = Metrics.of(ONE_CELL)
        provideComposable {
            Donut(
                metrics = metrics,
                ring = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
                label = "08012345678",
                remaining = "60.00 GB",
                total = "120.00 GB",
                fetchedAt = FETCHED_AT,
            )
        }

        onNode(hasText("残り")).assertExists()
        onNode(hasText("60.00 GB")).assertExists()
        onNode(hasText("120.00 GB")).assertExists()
        onNode(hasText(stampText(FETCHED_AT, metrics.stampLines))).assertExists()
    }

    @Test
    fun `a never-fetched account degrades to a placeholder`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            Donut(
                metrics = Metrics.of(ONE_CELL),
                ring = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
                label = "08012345678",
                remaining = null,
                total = null,
                fetchedAt = null,
            )
        }

        onNode(hasText("未取得")).assertExists()
    }

    @Test
    fun `everything fits inside a single cell`() {
        val metrics = Metrics.of(ONE_CELL)

        assertTrue(
            "ring ${metrics.ring} exceeds the cell's width",
            metrics.ring <= ONE_CELL.width - metrics.padding * 2,
        )
        // The timestamp's own block, by the same approximation Metrics uses.
        val stampBlock = (metrics.stamp.value * 1.5f * metrics.stampLines).dp
        assertTrue(
            "ring + timestamp exceeds the cell's height",
            metrics.ring + metrics.gap + stampBlock <= ONE_CELL.height - metrics.padding * 2,
        )
    }

    @Test
    fun `a one-cell tile wraps the timestamp instead of clipping it`() {
        // "MM/dd HH:mm時点" does not fit one line at this width, so it must be
        // allowed two — and the break has to be the explicit one, since Android
        // would otherwise wrap in the middle of 時点.
        assertEquals(2, Metrics.of(ONE_CELL).stampLines)
        assertTrue(stampText(FETCHED_AT, lines = 2).contains("\n"))
        assertTrue(stampText(FETCHED_AT, lines = 2).endsWith("時点"))

        assertEquals(1, Metrics.of(DpSize(200.dp, 200.dp)).stampLines)
        assertTrue(!stampText(FETCHED_AT, lines = 1).contains("\n"))
    }

    @Test
    fun `a small ring is drawn thinner than a large one`() {
        // Otherwise the stroke eats the hole the readout has to sit in.
        assertTrue(Metrics.of(ONE_CELL).stroke < DonutRenderer.DEFAULT_STROKE_FRACTION)
        assertEquals(
            DonutRenderer.DEFAULT_STROKE_FRACTION,
            Metrics.of(DpSize(200.dp, 200.dp)).stroke,
            0.0001f,
        )
    }

    private companion object {
        /** A 1x1 cell on a stock Pixel grid — the widget's default footprint. */
        val ONE_CELL = DpSize(73.dp, 113.dp)
        const val FETCHED_AT = 1_757_200_000_000L
    }
}
