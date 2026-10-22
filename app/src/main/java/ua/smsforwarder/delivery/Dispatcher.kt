package ua.smsforwarder.delivery

import ua.smsforwarder.core.Message
import ua.smsforwarder.core.tr

import android.content.Context
import androidx.work.*
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.decodeFromString
import ua.smsforwarder.ForwarderApp
import ua.smsforwarder.core.wireJson
import ua.smsforwarder.data.Repository
import java.util.concurrent.TimeUnit

class Dispatcher(private val repo: Repository, private val context: Context, private val client: WebhookClient = WebhookClient()) {
    private val mutex = Mutex()
    suspend fun drain() {
        if (!mutex.tryLock()) return
        try { drainLocked() } finally { mutex.unlock() }
    }
    private suspend fun drainLocked() = withContext(Dispatchers.IO) {
        val dao = repo.dao
        val now = System.currentTimeMillis()
        dao.pruneDeliveries(now - 7 * RetryPolicy.DAY, now - 30 * RetryPolicy.DAY)
        dao.pruneEvents(now - 7 * RetryPolicy.DAY)
        dao.pruneCursors(now - 30 * RetryPolicy.DAY)
        repeat(50) {
            if (System.currentTimeMillis() - now > 240_000) { DeliveryWork.wake(context, 1000); return@withContext }
            if (!repo.settings.current().enabled) return@withContext
            val at = System.currentTimeMillis()
            val item = repo.db.withTransaction {
                val candidate = dao.next(at) ?: return@withTransaction null
                if (dao.claim(candidate.id, at, at + 120_000) == 1) candidate else null
            } ?: return@withContext
            if (at - item.retryStartedAt >= 7 * RetryPolicy.DAY) {
                dao.updateDelivery(item.copy(status = "failed", completedAt = at, leaseUntil = 0, lastError = tr(Message.THE_7_DAY_RETRY_WINDOW_HAS_EXPIRED)))
                return@repeat
            }
            val result = try {
                val headers = wireJson.decodeFromString<Map<String,String>>(repo.secrets.decrypt(item.encryptedHeaders))
                client.send(item.url, headers, item.payload, item.id)
            } catch (_: Exception) { SendResult(false, false, tr(Message.COULD_NOT_READ_SAVED_HEADERS_RECREATE_THE)) }
            val attempt = item.attempts + 1
            val done = System.currentTimeMillis()
            val delay = RetryPolicy.delay(attempt, result.retryAfterMs)
            val retry = !result.success && result.retry && done + delay - item.retryStartedAt < 7 * RetryPolicy.DAY
            // Deletion may cancel a claimed row; UPDATE must never resurrect it.
            dao.updateDelivery(item.copy(status = if (result.success) "sent" else if (retry) "pending" else "failed",
                attempts = attempt, nextAt = done + delay, leaseUntil = 0,
                lastError = result.message, completedAt = if (retry) null else done))
            if (retry) DeliveryWork.wake(context, delay)
        }
        DeliveryWork.wake(context, 1000)
    }
}
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as ForwarderApp).dispatcher.drain(); Result.success()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
object DeliveryWork {
    fun install(context: Context) {
        val request = PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("delivery-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun wake(context: Context, delayMs: Long = 0) {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        val key = if (delayMs == 0L) "delivery-now" else "delivery-${(System.currentTimeMillis() + delayMs) / 30000}"
        WorkManager.getInstance(context).enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
    }
}
