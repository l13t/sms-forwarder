package ua.smsforwarder.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "webhooks")
data class WebhookEntity(@PrimaryKey val id: String, val name: String, val url: String, val encryptedHeaders: String, val enabled: Boolean = true)
@Entity(tableName = "rules")
data class RuleEntity(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "events", indices = [Index(value = ["sourceKey"], unique = true)])
data class EventEntity(@PrimaryKey val id: String, val sourceKey: String, val createdAt: Long)
@Entity(tableName = "deliveries", indices = [Index("eventId"), Index("webhookId"), Index(value = ["eventId", "webhookId"], unique = true)])
data class DeliveryEntity(
    @PrimaryKey val id: String, val eventId: String, val webhookId: String,
    val payload: String, val url: String, val encryptedHeaders: String,
    val createdAt: Long, val status: String = "pending", val attempts: Int = 0,
    val retryStartedAt: Long = createdAt, val nextAt: Long = 0, val leaseUntil: Long = 0, val lastError: String? = null,
    val completedAt: Long? = null
)
@Entity(tableName = "cursors")
data class CursorEntity(@PrimaryKey val key: String, val value: String, val updatedAt: Long = System.currentTimeMillis())

@Dao
interface ForwarderDao {
    @Query("SELECT * FROM webhooks ORDER BY name") fun webhooks(): Flow<List<WebhookEntity>>
    @Query("SELECT * FROM webhooks") suspend fun allWebhooks(): List<WebhookEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWebhook(value: WebhookEntity)
    @Query("DELETE FROM webhooks WHERE id=:id") suspend fun deleteWebhook(id: String)
    @Query("DELETE FROM deliveries WHERE webhookId=:id AND status IN ('pending','sending')") suspend fun cancelWebhook(id: String)
    @Query("SELECT * FROM rules") fun rules(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules") suspend fun allRules(): List<RuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRule(value: RuleEntity)
    @Query("DELETE FROM rules WHERE id=:id") suspend fun deleteRule(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(value: EventEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDelivery(value: DeliveryEntity): Long
    @Query("SELECT * FROM deliveries ORDER BY createdAt DESC LIMIT 300") fun deliveries(): Flow<List<DeliveryEntity>>
    @Query("SELECT * FROM deliveries WHERE id=:id") suspend fun delivery(id: String): DeliveryEntity?
    @Query("""SELECT d.* FROM deliveries d INNER JOIN webhooks w ON w.id=d.webhookId
        WHERE w.enabled=1 AND ((d.status='pending' AND d.nextAt<=:now) OR (d.status='sending' AND d.leaseUntil<:now))
        ORDER BY d.createdAt LIMIT 1""") suspend fun next(now: Long): DeliveryEntity?
    @Query("""UPDATE deliveries SET status='sending', leaseUntil=:lease WHERE id=:id
        AND ((status='pending' AND nextAt<=:now) OR (status='sending' AND leaseUntil<:now))""")
    suspend fun claim(id: String, now: Long, lease: Long): Int
    @Update suspend fun updateDelivery(value: DeliveryEntity)
    @Query("UPDATE deliveries SET status='pending', attempts=0, nextAt=0, leaseUntil=0, completedAt=NULL, lastError=NULL, retryStartedAt=:now WHERE id=:id AND status!='sending'") suspend fun retry(id: String, now: Long)
    @Query("DELETE FROM deliveries WHERE status IN ('sent','failed')") suspend fun clearFinished()
    @Query("DELETE FROM deliveries WHERE (completedAt IS NOT NULL AND completedAt<:finishedBefore) OR createdAt<:allBefore") suspend fun pruneDeliveries(finishedBefore: Long, allBefore: Long)
    @Query("DELETE FROM events WHERE createdAt<:before AND id NOT IN (SELECT eventId FROM deliveries)") suspend fun pruneEvents(before: Long)
    @Query("DELETE FROM cursors WHERE `key` LIKE 'notification:%' AND updatedAt<:before") suspend fun pruneCursors(before: Long)
    @Query("SELECT count(*) FROM deliveries WHERE status IN ('pending','sending')") fun pendingCount(): Flow<Int>
    @Query("DELETE FROM cursors WHERE `key`=:key") suspend fun deleteCursor(key: String)
    @Query("SELECT value FROM cursors WHERE `key`=:key") suspend fun cursor(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cursor(value: CursorEntity)
}
@Database(entities = [WebhookEntity::class, RuleEntity::class, EventEntity::class, DeliveryEntity::class, CursorEntity::class], version = 1, exportSchema = false)
abstract class ForwarderDatabase : RoomDatabase() { abstract fun dao(): ForwarderDao }
