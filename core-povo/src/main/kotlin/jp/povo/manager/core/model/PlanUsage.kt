package jp.povo.manager.core.model

import kotlinx.serialization.Serializable

/**
 * The five data allowances the API reports separately.
 *
 * They are genuinely separate pots, not alternatives: a line can hold a base
 * allowance and topped-up data at the same time. Which pot the data lands in
 * depends on the contract — an account whose data all came from a topping shows
 * zero in [BASIC] and everything in [BOOST] — so anything that reports "data
 * remaining" has to consider all five rather than reading [BASIC].
 */
@Serializable
enum class DataBucketKind(val jsonKey: String, val label: String) {
    BASIC("basic", "基本"),
    EXTRA("extra", "追加"),
    BOOST("boost", "トッピング"),
    BONUS("bonus", "ボーナス"),
    PLUS("plus", "プラス"),
}

/**
 * One allowance pot.
 *
 * Sizes are kilobytes and arrive as **fractional** values (a real response
 * carried `58147676.87109375`), so they are held as [Double]. Parsing these as
 * integers throws.
 *
 * [planKb] is null for the buckets that do not report a ceiling — `bonus` and
 * `boost` omit `plan_kb` entirely.
 */
@Serializable
data class DataBucket(
    val kind: DataBucketKind,
    val usedKb: Double,
    val leftKb: Double,
    val planKb: Double? = null,
    val section: String? = null,
    val unit: String? = null,
    /** `boost` reports the smallest purchasable step, 1 GiB in kilobytes. */
    val sectionMinValueKb: Double? = null,
) {
    val isEmpty: Boolean get() = usedKb == 0.0 && leftKb == 0.0
}

/**
 * A line's data position at one moment.
 */
data class PlanUsage(
    val buckets: List<DataBucket>,
    val promotionLine1: String? = null,
    val promotionLine2: String? = null,
) {
    /** Total data still available across every pot. */
    val totalLeftKb: Double get() = buckets.sumOf { it.leftKb }

    val totalUsedKb: Double get() = buckets.sumOf { it.usedKb }

    /** Pots that carry any data, most remaining first — what a summary shows. */
    val activeBuckets: List<DataBucket>
        get() = buckets.filterNot { it.isEmpty }.sortedByDescending { it.leftKb }

    operator fun get(kind: DataBucketKind): DataBucket? = buckets.firstOrNull { it.kind == kind }

    companion object {
        /**
         * Renders kilobytes for display.
         *
         * The divisors are binary, which is not a guess: a real 120 GB contract
         * reported 125,829,121 KB, and only 1024-based division turns that back
         * into a round 120.
         */
        fun formatKb(kb: Double): String = when {
            kb >= 1024.0 * 1024.0 -> "%.2f GB".format(kb / (1024.0 * 1024.0))
            kb >= 1024.0 -> "%.1f MB".format(kb / 1024.0)
            else -> "%.0f KB".format(kb)
        }
    }
}
