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
        /**
         * 这个号码是否来自 SIM 卡。
         *
         * 按「号码」而不是「联系人」判断：一个联系人在系统里可能是多个 RawContact
         * 聚合出来的，SIM 卡那个和手机本地那个各自带着自己的号码，
         * 只有落到具体 RawContact 上才知道这个号存在哪。
         */
        val isSim: Boolean = false,
        /**
         * RawContact id。**LazyColumn 的 key 必须用它**：
         * 同一个联系人（contactId）可能有多条相同号码的记录 ——
         * 典型场景就是 SIM 卡和手机本地各存了一份，或者同一 RawContact 下
         * 同号码被录了两遍。用 contactId + number 做 key 会直接撞车抛
         * `IllegalArgumentException: Key was already used`，整个页面崩掉。
         */
        val rawId: Long = 0,
        /**
         * Data 表主键，**列表 key 用这个**。
         * rawId 也不够：实测同一个 RawContact 下可能存在两行完全相同的号码
         * （用户在通讯录里重复录入 / SIM 导入残留），只有 Data._ID 全局唯一。
         */
        val dataId: Long = 0,
        /**
         * 该联系人的**全部**号码。
         *
         * 列表上一个人只显示一条（[number] 是第一个号码），但拨号盘搜索必须
         * 能搜到他的其它号码 —— 否则「只显示第一个」就等于把第二个号码弄丢了。
         * 为空表示这个联系人只有一个号码。
         */
        val allNumbers: List<String> = emptyList(),
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

        /** 所有号码的数字形式，供搜索遍历；只有一个号码时就是 [digits] 本身 */
        val allDigits: List<String> by lazy {
            if (allNumbers.isEmpty()) {
                listOf(digits)
            } else {
                val more = allNumbers.mapNotNull { raw ->
                    PhoneLocationDb.normalize(raw)?.takeIf { it.isNotEmpty() }
                }
                (listOf(digits) + more).distinct()
            }
        }

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
            // 号码匹配遍历这个人的所有号码：列表上只显示第一个，
            // 但搜第二个号码也得能把人搜出来
            val numPrefix = e.allDigits.any { it.startsWith(digits) }
            val numContains = !numPrefix && e.allDigits.any { it.contains(digits) }
            val kind = when {
                numPrefix -> MatchKind.NUMBER_PREFIX
                e.t9Full.startsWith(digits) -> MatchKind.PINYIN_PREFIX
                e.t9Initials.startsWith(digits) -> MatchKind.INITIALS_PREFIX
                numContains -> MatchKind.NUMBER_CONTAINS
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

    /**
     * SIM 卡上的 RawContact id 集合。
     *
     * 系统没有「这张卡是不是 SIM」的直接字段，只能看 RawContact 的 account_type：
     * AOSP 是 `com.android.sim`，三星是 `vnd.sec.contact.sim`，
     * Sony 及运营商定制机还可能是别的名字。统一用「类型里含 sim」判断，
     * 因为没有任何云账户类型会带 sim 这个词（本机实测：本地是
     * com.sonyericsson.localcontacts，云是 com.google）。
     *
     * RawContacts 单独查一次再和 Phone 的结果 join：Data/Phone 表本身不含
     * account_type，而一个联系人可能由 SIM + 本地两个 RawContact 聚合而成，
     * 所以必须精确到 RawContact 才能判断「这个号码」存在哪。
     */
    private fun simRawContactIds(context: Context): Set<Long> {
        val ids = HashSet<Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val typeIdx = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                while (c.moveToNext()) {
                    val type = c.getString(typeIdx).orEmpty()
                    if (type.contains("sim", ignoreCase = true)) ids.add(c.getLong(idIdx))
                }
            }
        }.onFailure { Log.w(TAG, "query raw contacts for sim flag failed", it) }
        return ids
    }

    /**
     * 同一个联系人的多个号码合并成一条，只保留排序后的第一个。
     *
     * Provider 返回的是「号码」维度：一个人存了手机号和座机，就会出两行，
     * 通讯录里看着像重名的两个人。这里按 contactId 收成一条，
     * 列表上只露主号码（完整号码在点开后的联系人卡片里看）。
     *
     * 依赖分组的行来自同一次按 SORT_KEY 排序的查询，同一联系人的行必然相邻；
     * 用 HashSet 保序去重，保留的是排序后最先出现的那条，
     * 它的 dataId / isSim 也随之成为这条记录的身份 —— 显示的号码在 SIM 卡上
     * 才会打 SIM 标，和列表上看到的内容保持一致。
     */
    private fun mergeByContact(list: List<Entry>): List<Entry> {
        val first = LinkedHashMap<Long, Entry>(list.size)
        val extra = HashMap<Long, MutableList<String>>()
        for (e in list) {
            if (!first.containsKey(e.id)) {
                first[e.id] = e
            } else {
                extra.getOrPut(e.id) { ArrayList(2) }.add(e.number)
            }
        }
        if (extra.isEmpty()) return first.values.toList()
        // 其余号码挂到 allNumbers 上，列表不显示但搜索能命中
        return first.values.map { e ->
            val more = extra[e.id] ?: return@map e
            e.copy(allNumbers = listOf(e.number) + more)
        }
    }

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
            // 用来判断这条号码存在 SIM 卡还是手机本地
            ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
            // Data 行主键，LazyColumn 的唯一 key
            ContactsContract.CommonDataKinds.Phone._ID,
        )
        val simRawIds = simRawContactIds(context)
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
                val rawIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
                val dataIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
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
                                isSim = rawIdx >= 0 && c.getLong(rawIdx) in simRawIds,
                                rawId = if (rawIdx >= 0) c.getLong(rawIdx) else 0L,
                                dataId = if (dataIdx >= 0) c.getLong(dataIdx) else 0L,
                            )
                        )
                    }
                }
            }?.let { list ->
                mergeByContact(list).take(limit).also { warmUp(it) }
            } ?: emptyList()
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
