package com.wangj.xperiadialer.data

import android.content.Context
import android.util.Log

/**
 * 手机号归属地查询（离线）。
 *
 * 数据来自 `assets/phone.dat`（xluohome/hiwjd phonedata，2025-11 版，517,878 条号段）。
 * 文件格式：
 * ```
 * [0..3]  4 字节标记（"2511" = 2025年11月）
 * [4..7]  4 字节 uint32 LE，索引区起始偏移
 * [8..indexOffset)  记录区，每条 "省|市|邮编|区号\0"
 * [indexOffset..EOF) 索引区，每条 9 字节：
 *                   4 字节 uint32 LE 号段前 7 位
 *                   4 字节 uint32 LE 记录偏移
 *                   1 字节 运营商类型
 * ```
 *
 * 索引区按号段升序排列，直接二分查找；517,878 条约 19 次比较，亚毫秒级。
 * 全部数据一次性读进内存（4.6MB），避免 CallScreeningService 5 秒超时内做 IO。
 */
object PhoneLocationDb {

    private const val TAG = "PhoneLocationDb"
    private const val ASSET_NAME = "phone.dat"

    data class Info(
        val province: String,
        val city: String,
        val carrier: String,
    ) {
        /** 省 == 市时（直辖市）不重复显示 */
        val display: String
            get() = when {
                city.isBlank() || city == province -> province
                province.isBlank() -> city
                else -> "$province$city"
            }

        val isEmpty: Boolean get() = province.isBlank() && city.isBlank()
    }

    private val CARRIERS = arrayOf(
        "", "移动", "联通", "电信",
        "电信虚拟运营商", "联通虚拟运营商", "移动虚拟运营商",
        "中国广电", "广电虚拟运营商"
    )

    @Volatile
    private var data: ByteArray? = null

    @Volatile
    private var indexOffset: Int = 0

    @Volatile
    private var recordCount: Int = 0

    private var loadFailed = false

    val isLoaded: Boolean get() = data != null

    /** 首次查询时加载，失败也不再重试（避免每次来电都卡在 IO 上） */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        data?.let { return true }
        if (loadFailed) return false
        return runCatching {
            val bytes = context.applicationContext.assets.open(ASSET_NAME).use { it.readBytes() }
            require(bytes.size > 12) { "phone.dat too small" }
            indexOffset = readIntLE(bytes, 4)
            val remain = bytes.size - indexOffset
            require(indexOffset in 8 until bytes.size && remain % 9 == 0) {
                "bad phone.dat structure"
            }
            recordCount = remain / 9
            data = bytes
            Log.i(TAG, "loaded ${bytes.size} bytes, $recordCount segments, index@$indexOffset")
            true
        }.onFailure {
            Log.e(TAG, "load phone.dat failed", it)
            loadFailed = true
        }.getOrDefault(false)
    }

    /** @param number 任意格式的号码，内部会清洗 */
    fun lookup(context: Context, number: String): Info? {
        if (!ensureLoaded(context)) return null
        val bytes = data ?: return null

        val digits = normalize(number) ?: return null
        if (digits.length < 7) return null
        val prefix = digits.substring(0, 7).toIntOrNull() ?: return null

        var lo = 0
        var hi = recordCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = indexOffset + mid * 9
            if (p + 9 > bytes.size) return null
            val seg = readIntLE(bytes, p)
            when {
                seg == prefix -> {
                    val recOffset = readIntLE(bytes, p + 4)
                    val carrierIdx = bytes[p + 8].toInt() and 0xFF
                    val record = readCString(bytes, recOffset) ?: return null
                    val parts = record.split('|')
                    return Info(
                        province = parts.getOrElse(0) { "" },
                        city = parts.getOrElse(1) { "" },
                        carrier = CARRIERS.getOrElse(carrierIdx) { "" },
                    )
                }
                seg < prefix -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    /** 去掉 +86 / 86 前缀、空格、括号、横线 */
    fun normalize(raw: String): String? {
        var s = raw.filter { it.isDigit() }
        if (s.length == 13 && s.startsWith("86")) s = s.substring(2)
        if (s.length == 15 && s.startsWith("0086")) s = s.substring(4)
        return s.ifEmpty { null }
    }

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readCString(b: ByteArray, off: Int): String? {
        if (off < 0 || off >= b.size) return null
        var end = off
        while (end < b.size && b[end] != 0.toByte()) end++
        return runCatching { String(b, off, end - off, Charsets.UTF_8) }.getOrNull()
    }
}
