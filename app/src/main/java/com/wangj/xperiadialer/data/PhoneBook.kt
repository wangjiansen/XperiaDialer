package com.wangj.xperiadialer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache

/**
 * 黄页号码库：号码 → 机构名 / 分类 / logo。
 *
 * 数据由 `tools/gen_phonebook.py` 从 metowolf/vCards 转换而来：
 * - `assets/phonebook.tsv`        `号码\t机构名\t分类\tlogo文件名`
 * - `assets/logos/<hash>.webp`    160×160 白底圆形图标，317 个共约 780 KB
 *
 * ## 只在 App 内部使用
 * 这份数据**不写入系统通讯录**，也不参与联系人查询。它只用来在来电/通话记录上
 * 补一个「这是谁打来的」的名字，跟 `ContactsRepository`（真实联系人）是两条独立链路：
 * 真实的通讯录联系人永远优先，黄页只在通讯录解析不到名字时兜底显示。
 *
 * ## 为什么不用数据库
 * 数据规模只有几百条，读进 HashMap 不到 1ms，用 Room 或二进制索引都是过度设计。
 * 真正的要求只有一条：**号码查询必须是纯内存同步操作** —— [SpamAnalyzer.analyze] 在
 * CallScreeningService 的 5 秒窗口里被调用，超时系统直接解绑且手机不响铃。
 * logo 解码则走 [loadLogo]，只在 UI 层调用，不进筛查链路。
 *
 * ## 找不到 assets 时静默降级
 * 数据文件不随仓库分发（vCards 没有 LICENSE），构建时由脚本生成。
 * 缺失时 [ensureLoaded] 返回 false，[find] 返回 null，来电识别退回
 * [KnownNumbers] 的内置表，界面回落到字母头像，通话链路不受影响。
 */
object PhoneBook {

    private const val TAG = "PhoneBook"
    private const val ASSET_NAME = "phonebook.tsv"
    private const val LOGO_DIR = "logos/"

    data class Entry(
        val name: String,
        val category: String,
        /** assets/logos 下的文件名；空串表示这家机构没有图标 */
        val logo: String = "",
    )

    @Volatile
    private var table: Map<String, Entry>? = null

    private var loadFailed = false

    /**
     * logo 解码缓存。160×160 ARGB_8888 单张约 100 KB，8 MB 够放 80 张，
     * 足够覆盖一屏列表 + 来电界面，且不会因为滚动把内存吃光。
     */
    private val logoCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
                        val parts = line.split('\t')
                        if (parts.size < 2) return@forEach
                        val number = parts[0]
                        val name = parts[1]
                        if (number.isEmpty() || name.isEmpty()) return@forEach
                        map[number] = Entry(
                            name = name,
                            category = parts.getOrElse(2) { "" },
                            logo = parts.getOrElse(3) { "" },
                        )
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

    /** 按数字串取 logo 文件名的便捷方法，未命中或没有图标返回 null */
    fun logoOf(context: Context, digits: String): String? =
        find(context, digits)?.logo?.takeIf { it.isNotEmpty() }

    /**
     * 解码 logo。**只允许 UI 调用**，不要在 CallScreeningService 里碰。
     *
     * 同步解码 160×160 的 WebP 约 1-2ms，配合 LruCache 后列表滚动不会掉帧。
     */
    fun loadLogo(context: Context, file: String): Bitmap? {
        if (file.isEmpty()) return null
        logoCache.get(file)?.let { return it }
        return runCatching {
            context.applicationContext.assets.open(LOGO_DIR + file).use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()?.also { logoCache.put(file, it) }
    }
}
