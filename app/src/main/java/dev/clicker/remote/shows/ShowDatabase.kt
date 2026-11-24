package dev.clicker.remote.shows

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ShowDao {
    @Query("SELECT * FROM shows")
    fun all(): Flow<List<Show>>

    @Query("SELECT * FROM shows")
    suspend fun list(): List<Show>

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun get(id: Int): Show?

    @Upsert
    suspend fun upsert(show: Show)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun delete(id: Int)
}

/**
 * The show list. When [Show] changes, raise the version and add a migration (Room keeps each
 * version's schema under app/schemas) so an update never loses the household's list.
 */
@Database(
    entities = [Show::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // 2: per-person lists (owners)
)
abstract class ShowDatabase : RoomDatabase() {
    abstract fun shows(): ShowDao

    companion object {
        @Volatile private var instance: ShowDatabase? = null

        fun get(context: Context): ShowDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ShowDatabase::class.java, "shows.db")
                .build().also { instance = it }
        }
    }
}
