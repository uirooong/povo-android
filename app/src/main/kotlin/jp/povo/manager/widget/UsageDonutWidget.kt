package jp.povo.manager.widget

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import jp.povo.manager.MainActivity
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.data.AccountRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A square widget for one chosen account, showing its remaining data as a ring.
 *
 * Deliberately single-account, unlike [UsageWidget]: a ring only means anything
 * against one allowance, and the point of this widget is the at-a-glance
 * proportion rather than a comparison. Which account it shows is per-widget
 * state, so several can be placed side by side.
 */
class UsageDonutWidget : GlanceAppWidget() {

    /** Per-widget state, so each placed instance can track its own account. */
    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * The whole layout is derived from the real tile size, so the same widget
     * reads correctly in a single 1x1 cell and when the user stretches it.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val selectedId = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[ACCOUNT_KEY]

        val repo = AccountRepository.get(context)
        val accounts = repo.accounts()
        // Fall back to the only account when there is exactly one: a widget
        // placed without configuration is otherwise useless, and with a single
        // account there is nothing to choose.
        val account = accounts.firstOrNull { it.id == selectedId }
            ?: accounts.singleOrNull()
        val usage = account?.let { repo.usageSnapshot()[it.id] }

        val totalKb = usage?.let { it.totalLeftKb + it.totalUsedKb } ?: 0.0
        val fraction = if (totalKb > 0.0) (usage!!.totalLeftKb / totalKb).toFloat() else 0f

        Log.i(TAG, "rendering ${account?.id ?: "(unselected)"} at ${"%.3f".format(fraction)}")

        provideContent {
            GlanceTheme {
                when {
                    account == null -> Unconfigured()
                    else -> {
                        // The ring is drawn here rather than above because its
                        // stroke depends on the measured tile size, which only
                        // exists inside the composition.
                        val metrics = Metrics.of(LocalSize.current)
                        Donut(
                            metrics = metrics,
                            ring = DonutRenderer.render(
                                LocalContext.current,
                                fraction,
                                metrics.stroke,
                            ),
                            label = account.label ?: account.id,
                            remaining = usage?.let { PlanUsage.formatKb(it.totalLeftKb) },
                            total = usage?.let { PlanUsage.formatKb(totalKb) },
                            fetchedAt = usage?.fetchedAt,
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PovoDonutWidget"

        /** The account this widget instance shows. */
        val ACCOUNT_KEY = stringPreferencesKey("account_id")

        suspend fun refresh(context: Context) {
            runCatching { UsageDonutWidget().updateAll(context) }
                .onFailure { Log.w(TAG, "widget update failed", it) }
        }
    }
}

/**
 * Everything that changes with the tile size, worked out in one place.
 *
 * A 1x1 cell is only about 70dp by 110dp on a stock grid, so nothing here can
 * be a fixed size: the ring takes whichever dimension runs out first (after the
 * timestamp underneath has been given its line) and the type is then scaled off
 * the ring, which is what keeps the readout inside the hole at every size.
 */
internal class Metrics(
    val padding: Dp,
    val ring: Dp,
    val rule: Dp,
    val gap: Dp,
    val label: TextUnit,
    val value: TextUnit,
    val total: TextUnit,
    val stamp: TextUnit,
    val stampLines: Int,
    val stroke: Float,
) {
    companion object {
        fun of(size: DpSize): Metrics {
            val tight = minOf(size.width, size.height) < 140.dp
            val padding = if (tight) 2.dp else 10.dp
            val gap = if (tight) 1.dp else 6.dp
            val stamp = if (tight) 8.sp else 10.sp
            // A 1x1 cell is narrower than "MM/dd HH:mm時点" needs on one line, so
            // there the timestamp is allowed to wrap at its space rather than be
            // ellipsized - the ring is limited by the tile's width, which leaves
            // the vertical room for a second line going spare anyway.
            val stampLines = if (tight) 2 else 1
            // Glance cannot measure text, so those lines are reserved by
            // approximation before the rest is handed to the ring.
            val stampBlock = (stamp.value * 1.5f * stampLines).dp

            val ring = minOf(
                size.width - padding * 2,
                size.height - padding * 2 - gap - stampBlock,
            ).coerceIn(44.dp, 168.dp)

            return Metrics(
                padding = padding,
                ring = ring,
                rule = ring * 0.44f,
                gap = gap,
                label = (ring.value * 0.10f).coerceIn(7f, 12f).sp,
                value = (ring.value * 0.16f).coerceIn(9f, 22f).sp,
                total = (ring.value * 0.11f).coerceIn(7f, 13f).sp,
                stamp = stamp,
                stampLines = stampLines,
                // A proportionally thick stroke would crowd the readout out of
                // a small hole, so tight tiles get a slimmer ring.
                stroke = if (tight) 0.085f else DonutRenderer.DEFAULT_STROKE_FRACTION,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun Unconfigured() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(6.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "アカウントを選択",
            style = TextStyle(
                fontSize = 11.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )
    }
}

@androidx.compose.runtime.Composable
internal fun Donut(
    metrics: Metrics,
    ring: android.graphics.Bitmap,
    label: String,
    remaining: String?,
    total: String?,
    fetchedAt: Long?,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(metrics.padding)
            .clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ring),
                contentDescription = "$label のデータ残量",
                modifier = GlanceModifier.size(metrics.ring),
            )
            // The readout is real Glance text on top of the bitmap rather than
            // being drawn into it, so it stays sharp and follows the theme.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "残り",
                    style = TextStyle(
                        fontSize = metrics.label,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                Text(
                    remaining ?: "—",
                    style = TextStyle(
                        fontSize = metrics.value,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    maxLines = 1,
                )
                Spacer(
                    GlanceModifier
                        .width(metrics.rule)
                        .height(1.dp)
                        .background(GlanceTheme.colors.outline),
                )
                Text(
                    total ?: "—",
                    style = TextStyle(
                        fontSize = metrics.total,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(GlanceModifier.height(metrics.gap))
        Text(
            fetchedAt?.let { stampText(it, metrics.stampLines) } ?: "未取得",
            style = TextStyle(
                fontSize = metrics.stamp,
                color = GlanceTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            ),
            maxLines = metrics.stampLines,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

/**
 * `MM/dd HH:mm時点`, broken at its own space when it is allowed two lines.
 *
 * The break is explicit because Android wraps Japanese between any two
 * characters, which on a 1x1 tile put the line break inside `時点`.
 */
internal fun stampText(fetchedAt: Long, lines: Int): String {
    val stamp = "${AS_OF.format(Date(fetchedAt))}時点"
    return if (lines > 1) stamp.replaceFirst(" ", "\n") else stamp
}

private val AS_OF = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)

class UsageDonutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageDonutWidget()
}
