package com.wangj.xperiadialer.data

import android.content.Context
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log

/**
 * 通讯录读取。
 *
 * 用系统通讯录 provider（需要 READ_CONTACTS），不复制数据。
 *
 * 排序：`SORT_KEY_PRIMARY` 是系统已经算好的拼音排序键（如「张三」→ "ZHANG SAN"），
 * 拿它首字母做字母分组，比自己转拼音可靠 —— Android 没有公开的汉字转拼音 API。
 * 少数情况下 sort key 可能仍是中文，这类统一归到 "#" 分组。
 */
object ContactsRepository {

    private const val TAG = "ContactsRepo"

    data class Entry(
        val id: Long,
        val name: String,
        val number: String,
        val photoUri: String?,
        val sortKey: String,
        /** 是否在系统的「收藏 / 常用」里，用于「常用联系人」页 */
        val starred: Boolean = false,
        /** 联系人 lookup key，用于构造 QuickContact 的 lookup URI */
        val lookupKey: String = "",
    ) {
        /** 分组字母：A-Z，其他归 "#" */
        val initial: String
            get() {
                val first = sortKey.trim().firstOrNull() ?: name.trim().firstOrNull() ?: ' '
                return pinyinInitial(first).toString()
            }

        val displayName: String get() = name.ifBlank { number }

        // ---------- T9 拼音索引（懒计算，只在真正搜索时才付出转换开销） ----------

        /** 纯数字号码，用于号码匹配（同时剥掉 +86 前缀） */
        val digits: String by lazy { PhoneLocationDb.normalize(number).orEmpty() }

        /**
         * 联系人 lookup URI，用于打开系统快捷联系人卡片（QuickContact）。
         *
         * 必须用 lookup 形式（`contacts/lookup/<key>/<id>`）。直接拼
         * `contacts/<id>` 不行 —— AOSP 的 QuickContactActivity 用
         * `QuickContact.resolveQuickContactUrl` 解析，它只认 lookup URI，
         * 解析失败会立刻 finish()，表现就是「点了头像什么都没发生」。
         */
        val contactUri: String by lazy {
            ContactsContract.Contacts.getLookupUri(id, lookupKey).toString()
        }

        /** 姓名全拼 T9。张三 → zhangsan → 94264726 */
        val t9Full: String by lazy { T9Engine.pinyinToT9(name) }

        /** 姓名首字母 T9。张三 → zs → 97 */
        val t9Initials: String by lazy {
            // 正常情况下走 ICU 拼音；ICU 数据被 ROM 裁掉时降级到 GBK 逐字首字母，
            // 至少保留「首字母搜人」这条最常用的路径
            T9Engine.initialsToT9(name).ifEmpty { T9Engine.toDigits(gbkInitials(name)) }
        }
    }

    /** 逐字取拼音首字母（GBK 区间算法），ICU 不可用时的兜底 */
    private fun gbkInitials(text: String): String = buildString {
        for (ch in text) {
            val c = pinyinInitial(ch)
            if (c != '#') append(c)
        }
    }

    /** 匹配类型，值越小排序越靠前 */
    enum class MatchKind {
        NUMBER_PREFIX,     // 号码前缀：直接键入已有号码
        PINYIN_PREFIX,     // 全拼前缀：9426 命中「张三」
        INITIALS_PREFIX,   // 首字母前缀：97 命中「张三」
        NUMBER_CONTAINS,   // 号码中段
        PINYIN_CONTAINS,   // 拼音中段
    }

    data class Hit(val entry: Entry, val kind: MatchKind)

    /**
     * T9 搜索。
     *
     * 输入只取其中的数字（用户可能按了 `*`/`#`），然后同时匹配号码与拼音。
     * 号码匹配排前面 —— 用户已经在敲号码时不该被同名联系人盖住。
     *
     * 输入 1 位也照搜：`1` 会列出号码以 1 开头的联系人，`9` 会列出 w/x/y/z 段，
     * 这比「按了一位却什么都没发生」更符合直觉。
     *
     * 索引是懒计算的：第一次搜索会把全部姓名的拼音转一遍（几百条通常几十毫秒），
     * 之后都命中缓存。
     */
    fun search(entries: List<Entry>, query: String, limit: Int = 30): List<Hit> {
        val digits = query.filter { it.isDigit() }
        // 全是 * / # 时数字为空，没什么可搜的
        if (digits.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>(16)
        for (e in entries) {
            val kind = when {
                e.digits.startsWith(digits) -> MatchKind.NUMBER_PREFIX
                e.t9Full.startsWith(digits) -> MatchKind.PINYIN_PREFIX
                e.t9Initials.startsWith(digits) -> MatchKind.INITIALS_PREFIX
                e.digits.contains(digits) -> MatchKind.NUMBER_CONTAINS
                e.t9Full.contains(digits) -> MatchKind.PINYIN_CONTAINS
                else -> null
            } ?: continue
            hits.add(Hit(e, kind))
        }
        /*
         * 必须遍历完整个通讯录。
         *
         * 曾经在这里加过「收够 N 条就 break」的提前退出，结果很隐蔽地出错：
         * 通讯录是按拼音排序的，输入 94 时 w 段（王/吴/魏…）先命中一堆首字母匹配，
         * 缓存满了就 break，永远走不到 x 段的「相晨光」「小号」—— 而这些才是
         * 排序更靠前的拼音前缀匹配。几百条字符串比较是微秒级，没必要省。
         *
         * stable 排序：同一档内保持通讯录原有的拼音顺序。
         */
        return hits.sortedBy { it.kind.ordinal }.take(limit)
    }

    /** 号码完全相等（忽略格式差异），用于在结果里标记「已是联系人」 */
    fun findByNumber(entries: List<Entry>, number: String): Entry? {
        val d = PhoneLocationDb.normalize(number) ?: return null
        return entries.firstOrNull { it.digits == d }
    }

    /**
     * 汉字转拼音首字母。
     *
     * 系统的 SORT_KEY_PRIMARY 在中英文混排时表现不一致：英文名返回 "YF1" 这种拼音串，
     * 中文名却直接返回汉字本身，直接取首字符会导致所有中文联系人都掉进 "#" 组。
     *
     * 这里用经典的 GBK 区位码区间算法：把汉字编成 GBK 双字节后算区位码，
     * 对照拼音字母的起始码区间反查首字母。不需要额外依赖，AVD/真机通用。
     */
    private fun pinyinInitial(c: Char): Char {
        if (c in 'A'..'Z') return c
        if (c in 'a'..'z') return c.uppercaseChar()
        if (c in '0'..'9') return '#'
        val bytes = runCatching { c.toString().toByteArray(charset("GBK")) }.getOrNull()
            ?: return '#'
        if (bytes.size < 2) return '#'
        val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF) - 65536
        if (code < PY_BOUND[0] || code > -10247) return '#'
        var letter = '#'
        for (i in PY_BOUND.indices) {
            if (code >= PY_BOUND[i]) letter = PY_LETTER[i] else break
        }
        return letter
    }

    /** 各拼音字母在 GBK 区位码中的起始值（拼音无 I / U / V） */
    private val PY_BOUND = intArrayOf(
        -20319, -20283, -19775, -19218, -18710, -18526,
        -18239, -17922, -17417, -16474, -16212, -15640,
        -15165, -14922, -14914, -14630, -14149, -14090,
        -13318, -12838, -12556, -11847, -11055,
    )
    private const val PY_LETTER = "ABCDEFGHJKLMNOPQRSTWXYZ"

    fun query(context: Context, limit: Int = 2000): List<Entry> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.Contacts.SORT_KEY_PRIMARY,
            // 收藏标记：系统的「常用联系人」就是靠它
            ContactsContract.CommonDataKinds.Phone.STARRED,
            // QuickContact 需要 lookup key 才能构造 lookup URI
            ContactsContract.Contacts.LOOKUP_KEY,
        )
        return runCatching {
            /*
             * 注意：SORT_KEY_PRIMARY 这个常量的值本身已经带 " ASC"（形如
             * "phonebook_bucket ASC, sort_key ASC"），不能再拼 " ASC"，
             * 否则生成 "ORDER BY ... ASC LIMIT n, sort_key ASC LIMIT n" 直接语法错误。
             * 条数限制改在内存里做。
             */
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val sortIdx = c.getColumnIndex(ContactsContract.Contacts.SORT_KEY_PRIMARY)
                val starredIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val lookupIdx = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(nameIdx).orEmpty()
                        val number = c.getString(numIdx).orEmpty()
                        if (number.isBlank()) continue
                        add(
                            Entry(
                                id = c.getLong(idIdx),
                                name = name,
                                number = number,
                                photoUri = c.getString(photoIdx),
                                sortKey = if (sortIdx >= 0) c.getString(sortIdx).orEmpty() else name,
                                starred = starredIdx >= 0 && c.getInt(starredIdx) != 0,
                                lookupKey = if (lookupIdx >= 0) c.getString(lookupIdx).orEmpty() else "",
                            )
                        )
                    }
                }
            }?.let { list -> list.take(limit).also { warmUp(it) } } ?: emptyList()
        }.onFailure {
            Log.w(TAG, "query contacts failed", it)
        }.getOrDefault(emptyList())
    }

    /**
     * 预热 T9 索引。
     *
     * 拼音转换是逐条调 ICU 的，几百个联系人一次性算完要几十到几百毫秒。
     * 必须在 IO 线程（[query] 的调用点）把 lazy 值提前 touch 掉，
     * 否则第一次在拨号盘输数字时会卡住主线程。
     */
    private fun warmUp(entries: List<Entry>) {
        val t0 = SystemClock.elapsedRealtime()
        var withPinyin = 0
        for (e in entries) {
            if (e.t9Full.isNotEmpty()) withPinyin++
            @Suppress("UNUSED_EXPRESSION") e.t9Initials
        }
        Log.i(
            TAG,
            "T9 index warmed: ${entries.size} contacts, $withPinyin with pinyin, " +
                "${SystemClock.elapsedRealtime() - t0}ms (icu=${T9Engine.available})"
        )
    }

    /**
     * 按号码反查联系人姓名，用于给通话记录补名字
     * （CallLog 里的 cached_name 有时是空的）
     */
    fun lookupName(context: Context, number: String): String? {
        val digits = PhoneLocationDb.normalize(number) ?: return null
        if (digits.length < 5) return null
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.onFailure { Log.w(TAG, "lookupName failed", it) }.getOrNull()
    }
}
