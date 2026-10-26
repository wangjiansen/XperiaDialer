package com.wangj.xperiadialer.data

import android.content.Context
import android.util.Log

/**
 * 黄页号码库：号码 → 机构名 / 分类。
 *
 * 数据由 `tools/gen_phonebook.py` 从 metowolf/vCards 转换而来，落在
 * `assets/phonebook.tsv`（数百条，几十 KB），格式为 `号码\t机构名\t分类`。
 *
 * ## 为什么不用数据库
 * 数据规模只有几百条，读进 HashMap 不到 1ms，用 Room 或二进制索引都是过度设计。
 * 真正的要求只有一条：**查询必须是纯内存同步操作** —— [SpamAnalyzer.analyze] 在
 * CallScreeningService 的 5 秒窗口里被调用，超时系统直接解绑且手机不响铃。
 *
 * ## 找不到 assets 时静默降级
 * 数据文件不随仓库分发（vCards 没有 LICENSE），构建时由脚本生成。
 * 缺失时 [ensureLoaded] 返回 false，[find] 返回 null，来电识别退回
 * [KnownNumbers] 的内置表，不影响通话链路。
 */
object PhoneBook {

    private const val TAG = "PhoneBook"
    private const val ASSET_NAME = "phonebook.tsv"

    data class Entry(val name: String, val category: String)

    @Volatile
    private var table: Map<String, Entry>? = null

    private var loadFailed = false

    val isLoaded: Boolean get() = table != null

    val size: Int get() = table?.size ?: 0

    /** 首次查询时加载；失败后不再重试，避免每次来电都卡在 IO 上 */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        table?.let { return true }
        if (loadFailed) return false
        return runCatching {
            val rows: Map<String, Entry> = context.applicationContext.assets
                .open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    val map = HashMap<String, Entry>(1024)
                    lines.forEach { line ->
                        if (line.isEmpty()) return@forEach
                        val first = line.indexOf('\t')
                        if (first <= 0) return@forEach
                        val second = line.indexOf('\t', first + 1)
                        val number = line.substring(0, first)
                        val name = if (second < 0) line.substring(first + 1) else line.substring(first + 1, second)
                        val category = if (second < 0) "" else line.substring(second + 1)
                        if (name.isNotEmpty()) map[number] = Entry(name, category)
                    }
                    map
                }
            require(rows.isNotEmpty()) { "phonebook.tsv is empty" }
            table = rows
            Log.i(TAG, "loaded ${rows.size} entries")
            true
        }.onFailure {
            Log.w(TAG, "phonebook not available, fallback to builtin table", it)
            loadFailed = true
        }.getOrDefault(false)
    }

    /**
     * @param digits 已归一化的数字串（来自 [PhoneLocationDb.normalize]）
     */
    fun find(context: Context, digits: String): Entry? {
        if (digits.isEmpty()) return null
        if (!ensureLoaded(context)) return null
        val map = table ?: return null

        map[digits]?.let { return it }

        // 带区号的固话：来电可能带 0 也可能不带，两边都试一次
        if (digits.length >= 9 && digits[0] == '0') {
            map[digits.substring(1)]?.let { return it }
        }
        return null
    }
}
