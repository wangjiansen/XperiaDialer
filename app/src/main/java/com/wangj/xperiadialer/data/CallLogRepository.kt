package com.wangj.xperiadialer.data

import android.content.Context
import android.provider.CallLog
import android.util.Log

/**
 * 通话记录读取。
 *
 * 直接查系统 CallLog provider（需要 READ_CALL_LOG），不自己维护一份 ——
 * 系统那份是所有拨号器共享的，自建只会产生「两边记录不一致」的问题。
 */
object CallLogRepository {

    private const val TAG = "CallLogRepo"

    /** 与 CallLog.Calls.TYPE 对应 */
    const val TYPE_INCOMING = CallLog.Calls.INCOMING_TYPE
    const val TYPE_OUTGOING = CallLog.Calls.OUTGOING_TYPE
    const val TYPE_MISSED = CallLog.Calls.MISSED_TYPE
    const val TYPE_REJECTED = CallLog.Calls.REJECTED_TYPE
    const val TYPE_BLOCKED = CallLog.Calls.BLOCKED_TYPE

    data class Entry(
        val id: Long,
        val number: String,
        val name: String,
        val type: Int,
        val dateMillis: Long,
        val durationSec: Long,
        val location: String,
        /** 联系人的 lookup URI，用于点头像打开系统快捷联系人卡片；陌生号码为空 */
        val lookupUri: String? = null,
    ) {
        val displayName: String get() = name.ifBlank { number.ifBlank { "未知号码" } }
        val isMissed: Boolean get() = type == TYPE_MISSED
        val isOutgoing: Boolean get() = type == TYPE_OUTGOING
    }

    /**
     * @param limit 最多取多少条，避免首次打开一次性查出几千条卡住 UI
     */
    fun query(context: Context, limit: Int = 500): List<Entry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.GEOCODED_LOCATION,
            // 系统已经帮我们缓存了联系人的 lookup URI，点进去就是快捷联系人卡片
            CallLog.Calls.CACHED_LOOKUP_URI,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val locIdx = c.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)
                val lookupIdx = c.getColumnIndex(CallLog.Calls.CACHED_LOOKUP_URI)
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Entry(
                                id = c.getLong(idIdx),
                                number = c.getString(numIdx).orEmpty(),
                                name = c.getString(nameIdx).orEmpty(),
                                type = c.getInt(typeIdx),
                                dateMillis = c.getLong(dateIdx),
                                durationSec = c.getLong(durIdx),
                                location = if (locIdx >= 0) c.getString(locIdx).orEmpty() else "",
                                lookupUri = if (lookupIdx >= 0) c.getString(lookupIdx) else null,
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure {
            Log.w(TAG, "query call log failed", it)
        }.getOrDefault(emptyList())
    }

    /** 删除单条记录 */
    fun delete(context: Context, id: Long): Boolean = runCatching {
        context.contentResolver.delete(
            CallLog.Calls.CONTENT_URI,
            "${CallLog.Calls._ID} = ?",
            arrayOf(id.toString())
        ) > 0
    }.onFailure { Log.w(TAG, "delete failed", it) }.getOrDefault(false)
}
