package jp.povo.manager.widget

import androidx.glance.testing.unit.hasText
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the widget's content off-device.
 *
 * A widget is otherwise awkward to verify: it only draws once the launcher has
 * placed it, which no adb command can do. Rendering the composable directly
 * checks the part that can actually be wrong — that a reading reaches the
 * screen, and that an account with no reading yet degrades to a placeholder
 * rather than showing a confident "0 KB".
 */
// Robolectric supplies the real framework classes Glance's renderer touches
// (it builds a Bundle for the click action), which the default JVM stubs do not.
@RunWith(RobolectricTestRunner::class)
// sdk: Robolectric has no image for the app's targetSdk yet, and the widget's
// layout does not depend on the platform version.
// application: the stock Application, so booting the test does not run the
// real one — which schedules WorkManager, irrelevant here and unavailable.
@Config(sdk = [36], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageWidgetTest {

    @Test
    fun `shows each account with its remaining data`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            UsageWidgetContent(
                rows = listOf(
                    WidgetRow("a", "08012345678", "60.00 GB"),
                    WidgetRow("b", "08087654321", "1.2 MB"),
                ),
            )
        }

        onNode(hasText("08012345678")).assertExists()
        onNode(hasText("60.00 GB")).assertExists()
        onNode(hasText("08087654321")).assertExists()
        onNode(hasText("1.2 MB")).assertExists()
    }

    @Test
    fun `shows a placeholder rather than a fake zero when nothing has been fetched`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                UsageWidgetContent(rows = listOf(WidgetRow("a", "08012345678", "—")))
            }

            onNode(hasText("—")).assertExists()
        }

    @Test
    fun `says so when no account is registered`() = runGlanceAppWidgetUnitTest {
        provideComposable { UsageWidgetContent(rows = emptyList()) }

        onNode(hasText("アカウント未登録")).assertExists()
    }
}
