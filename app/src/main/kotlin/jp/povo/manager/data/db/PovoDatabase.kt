package jp.povo.manager.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, label")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, label")
    suspend fun all(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE accounts SET lastRefreshedAt = :at, lastError = NULL, blockedUntil = NULL WHERE id = :id")
    suspend fun markRefreshed(id: String, at: Long)

    @Query("UPDATE accounts SET lastError = :error, blockedUntil = :blockedUntil WHERE id = :id")
    suspend fun markFailed(id: String, error: String, blockedUntil: Long?)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_snapshots WHERE accountId = :accountId")
    fun observe(accountId: String): Flow<UsageSnapshotEntity?>

    @Query("SELECT * FROM usage_snapshots")
    fun observeAll(): Flow<List<UsageSnapshotEntity>>

    @Query("SELECT * FROM usage_snapshots")
    suspend fun all(): List<UsageSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: UsageSnapshotEntity)

    @Query("DELETE FROM usage_snapshots WHERE accountId = :accountId")
    suspend fun delete(accountId: String)
}

@Dao
interface BillDao {
    // Upcoming estimates carry no timestamp, and SQLite sorts NULLs last in
    // DESC — which would bury the next bill under a year of paid ones. The
    // first term lifts them back to the top.
    @Query(
        "SELECT * FROM bills WHERE accountId = :accountId " +
            "ORDER BY (timeEpochMillis IS NULL) DESC, timeEpochMillis DESC",
    )
    fun observe(accountId: String): Flow<List<BillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bills: List<BillEntity>)

    @Query("DELETE FROM bills WHERE accountId = :accountId")
    suspend fun deleteFor(accountId: String)

    /**
     * Replaces an account's billing rows wholesale.
     *
     * The API returns the timeline as a complete document each time, so merging
     * would leave behind rows the server has since dropped.
     */
    @Transaction
    suspend fun replaceFor(accountId: String, bills: List<BillEntity>) {
        deleteFor(accountId)
        insertAll(bills)
    }
}

@Dao
interface ExtrasDao {
    @Query("SELECT * FROM account_extras WHERE accountId = :accountId")
    fun observe(accountId: String): Flow<AccountExtrasEntity?>

    @Query("SELECT * FROM account_extras WHERE accountId = :accountId")
    suspend fun get(accountId: String): AccountExtrasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extras: AccountExtrasEntity)

    @Query("DELETE FROM account_extras WHERE accountId = :accountId")
    suspend fun delete(accountId: String)
}

@Dao
interface WebPageDao {
    @Query("SELECT * FROM web_pages WHERE accountId = :accountId")
    fun observe(accountId: String): Flow<List<WebPageEntity>>

    @Query("SELECT * FROM web_pages WHERE accountId = :accountId AND kind = :kind")
    suspend fun get(accountId: String, kind: String): WebPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<WebPageEntity>)

    @Query("DELETE FROM web_pages WHERE accountId = :accountId")
    suspend fun deleteFor(accountId: String)

    /**
     * Replaces an account's links wholesale.
     *
     * Called only when the profile page was actually read: a throttled refresh
     * skips that request, and merging nothing over the old rows would drop
     * every entry point until the next full run.
     */
    @Transaction
    suspend fun replaceFor(accountId: String, pages: List<WebPageEntity>) {
        deleteFor(accountId)
        insertAll(pages)
    }
}

@Database(
    entities = [
        AccountEntity::class,
        UsageSnapshotEntity::class,
        BillEntity::class,
        AccountExtrasEntity::class,
        WebPageEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class PovoDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun usage(): UsageDao
    abstract fun bills(): BillDao
    abstract fun extras(): ExtrasDao
    abstract fun webPages(): WebPageDao

    companion object {
        @Volatile
        private var instance: PovoDatabase? = null

        fun get(context: Context): PovoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PovoDatabase::class.java,
                "povo.db",
            )
                // This database is a cache: credentials live in the Keystore-backed
                // session store, and every row here can be refetched. Dropping it on
                // a schema change costs one refresh and avoids carrying migrations
                // for data that is disposable by design.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { instance = it }
        }
    }
}
