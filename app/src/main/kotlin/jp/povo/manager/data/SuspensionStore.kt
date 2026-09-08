package jp.povo.manager.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import jp.povo.manager.core.model.Suspension
import jp.povo.manager.core.model.SuspensionForecast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * When each line's 180-day idle clock started, as entered by the reader.
 *
 * See [Suspension] for why this is typed in rather than read from the service.
 *
 * **Deliberately not in Room.** [jp.povo.manager.data.db.PovoDatabase] is built
 * with `fallbackToDestructiveMigration(dropAllTables = true)`, which is right
 * for a cache — every row there can be fetched again. This cannot: it is the
 * one thing in the app the service cannot re-supply, so losing it on a schema
 * change would mean asking the reader to look up and retype a date they already
 * gave. DataStore survives that, which is the same argument [SettingsStore]
 * already makes for the theme.
 */
class SuspensionStore(private val context: Context) {

    /** Every stored anchor, keyed by account id. */
    val anchors: Flow<Map<String, SuspensionAnchor>> =
        context.settingsDataStore.data.map { decode(it[ANCHORS_KEY]) }

    /** Stores [anchor], or clears the account's entry when it is null. */
    suspend fun setAnchor(accountId: String, anchor: SuspensionAnchor?) {
        edit { current ->
            if (anchor == null) current - accountId else current + (accountId to anchor)
        }
    }

    /**
     * Records that the reader has been told about [suspendsOn] for this
     * account, so the 15-minute refresh does not tell them again.
     *
     * Stored as the date itself rather than a flag: buying a topping moves the
     * date, and a different date is what re-arms the alert. A flag would have
     * to be cleared by something, and nothing is watching.
     */
    suspend fun markNotified(accountId: String, suspendsOn: LocalDate) {
        edit { current ->
            val existing = current[accountId] ?: return@edit current
            current + (accountId to existing.copy(notifiedFor = suspendsOn.toString()))
        }
    }

    // Deliberately no "drop anchors for accounts that no longer exist". Room is
    // wiped on any schema change while this store is not, so an anchor with no
    // account is usually the reader's own typed-in date waiting for the account
    // to come back — sweeping it away would destroy the one thing here that
    // cannot be fetched again. Removing an account clears its anchor
    // explicitly; nothing else should.

    private suspend fun edit(
        transform: (Map<String, SuspensionAnchor>) -> Map<String, SuspensionAnchor>,
    ) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(decode(prefs[ANCHORS_KEY]))
            prefs[ANCHORS_KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): Map<String, SuspensionAnchor> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, SuspensionAnchor>>(raw) }
            .onFailure { Log.w(TAG, "could not read stored anchors", it) }
            .getOrDefault(emptyMap())
    }

    private companion object {
        const val TAG = "PovoSuspension"

        /**
         * One key holding the whole map rather than a key per account: the
         * screens want every anchor at once, and DataStore offers no way to
         * enumerate keys by prefix.
         */
        val ANCHORS_KEY = stringPreferencesKey("suspension_anchors")

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * One line's anchor: what was last bought, and when it runs out.
 *
 * [expiryDate] is what the arithmetic uses. [purchaseDate] and [durationDays]
 * are kept so the dialog can reopen showing what was actually typed — someone
 * who entered "365 日間" should not find a bare date there next time.
 */
@Serializable
data class SuspensionAnchor(
    /** `YYYY-MM-DD`. */
    val purchaseDate: String,
    /** Set when the period was entered as a length; null when a date was. */
    val durationDays: Int? = null,
    /** `YYYY-MM-DD`, resolved at entry. */
    val expiryDate: String,
    /** `YYYY-MM-DD` of the suspension date already notified about. */
    val notifiedFor: String? = null,
) {
    fun forecast(today: LocalDate = LocalDate.now()): SuspensionForecast? =
        Suspension.forecast(expiryDate, today)
}
