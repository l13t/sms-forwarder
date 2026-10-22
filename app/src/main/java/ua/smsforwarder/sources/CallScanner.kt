package ua.smsforwarder.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.smsforwarder.core.Event
import ua.smsforwarder.core.EventData
import ua.smsforwarder.data.CursorEntity
import ua.smsforwarder.data.Repository

class CallScanner(private val context: Context, private val repo: Repository) {
    private val mutex = Mutex()
    private fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    suspend fun baseline() = mutex.withLock {
        if (!allowed()) return@withLock
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC")?.use {
            repo.dao.cursor(CursorEntity("calls", if (it.moveToFirst()) it.getLong(0).toString() else "0"))
        }
    }
    suspend fun scan() = mutex.withLock {
        val settings = repo.settings.current()
        if (!allowed() || !settings.enabled || !settings.calls) return@withLock
        val last = repo.dao.cursor("calls")?.toLongOrNull()
        if (last == null) {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC")?.use {
                repo.dao.cursor(CursorEntity("calls", if (it.moveToFirst()) it.getLong(0).toString() else "0"))
            }
            return@withLock
        }
        val columns = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, columns, "${CallLog.Calls._ID} > ?", arrayOf(last.toString()), "${CallLog.Calls._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = when (c.getInt(4)) {
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> null
                }
                if (type != null) repo.accept(Event(type = "call.completed", occurredAt = c.getLong(2),
                    data = EventData(number = c.getString(1)?.takeIf { it.isNotBlank() }, durationSeconds = c.getLong(3), callType = type, simId = c.getString(5)),
                    sourceKey = "call:$id:${c.getLong(2)}"))
                repo.dao.cursor(CursorEntity("calls", id.toString()))
            }
        }
    }
}
