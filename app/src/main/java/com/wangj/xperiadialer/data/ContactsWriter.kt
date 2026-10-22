package com.wangj.xperiadialer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log

/**
 * 联系人读写。
 *
 * 本机 Sony 通讯录的 QuickContact 卡片和编辑页都打不开（详见
 * [com.wangj.xperiadialer.ui.ContactActions] 注释），所以卡片和编辑全部自研，
 * 直接读写系统 ContactsProvider —— 数据仍是系统那一份，别的 App 看到的完全一致。
 *
 * ## 字段覆盖（对齐原机编辑页）
 * 姓名、昵称、公司 / 部门 / 职位、电话（多行）、邮箱（多行）、
 * 地址（街道 / 城市 / 省 / 邮编，多行）、网站（多行）、生日、备注（多行）。
 *
 * 拼音字段原机也有，但那是给输入法/排序用的，中文输入自动能算，
 * 界面上不暴露；排序继续用系统给的 SORT_KEY。
 *
 * ## 存储结构
 * 所有字段都在 `Data` 表里，一行一个 mimetype：
 * - `StructuredName`  DATA1=显示名 DATA2=名 DATA3=姓
 * - `Nickname`        DATA1=昵称
 * - `Organization`    DATA1=公司 DATA4=职位 DATA5=部门
 * - `Phone`/`Email`   DATA1=值 DATA2=类型
 * - `StructuredPostal` DATA4=街道 DATA7=城市 DATA8=省 DATA9=邮编
 * - `Website`         DATA1=网址
 * - `Event`           DATA1=日期 DATA2=类型（3 = 生日）
 * - `Note`            DATA1=备注
 *
 * `Contacts` 聚合表只读，改完 provider 会异步重算显示名，所以写操作结束前
 * 要等一下，否则调用方刷新列表拿到的还是旧名字。
 *
 * ## 只重写自己认识的字段
 * 头像、群组、IM、关系、自定义字段这些不碰 —— 保存时按 mimetype 精确替换，
 * 别的行原样保留。
 */
object ContactsWriter {

    private const val TAG = "ContactsWriter"

    /** 聚合表的重新计算是异步的，改完名字后等一小会儿再让 UI 刷新 */
    private const val AGGREGATE_SETTLE_MS = 220L

    /**
     * 生日的 mimetype。
     *
     * **不能用 `Event.CONTENT_ITEM_TYPE`** —— 那个常量的值是历史遗留的
     * `vnd.android.cursor.item/contact_event`，而 provider 实际存储、返回的都是
     * `vnd.android.cursor.item/event`。用它去比较永远不相等（生日读不出来），
     * 用它去写会插进一个没人认的行（生日在系统通讯录里看不见）。
     */
    private const val MIME_EVENT = "vnd.android.cursor.item/event"

    // ------------------------------------------------------------------ 模型

    /** 单值行：电话 / 邮箱 / 网站 */
    data class Item(val dataId: Long, val value: String, val type: Int)

    /** 地址行 */
    data class Postal(
        val dataId: Long,
        val street: String,
        val city: String,
        val region: String,
        val postcode: String,
        val type: Int,
    ) {
        val isBlank: Boolean
            get() = street.isBlank() && city.isBlank() &&
                region.isBlank() && postcode.isBlank()
    }

    /**
     * 一个联系人的完整快照。
     *
     * [rawContactId] 是写回时的定位信息；[phones] 等列表里的 dataId 用于逐行替换。
     */
    data class Detail(
        val contactId: Long,
        val rawContactId: Long,
        val name: String,
        val starred: Boolean,
        val nickname: String = "",
        val company: String = "",
        val department: String = "",
        val jobTitle: String = "",
        val phones: List<Item> = emptyList(),
        val emails: List<Item> = emptyList(),
        val addresses: List<Postal> = emptyList(),
        val websites: List<Item> = emptyList(),
        val birthday: String = "",
        val note: String = "",
    ) {
        val primaryNumber: String get() = phones.firstOrNull()?.value.orEmpty()
        val displayName: String get() = name.ifBlank { primaryNumber }

        /** 快照 → 表单值，供「只改一个字段」这类局部更新复用 */
        fun toDraft(): Draft = Draft(
            name = name,
            nickname = nickname,
            company = company,
            department = department,
            jobTitle = jobTitle,
            phones = phones.map { DraftItem(it.value, it.type) },
            emails = emails.map { DraftItem(it.value, it.type) },
            addresses = addresses.map {
                DraftPostal(it.street, it.city, it.region, it.postcode, it.type)
            },
            websites = websites.map { DraftItem(it.value, it.type) },
            birthday = birthday,
            note = note,
            starred = starred,
        )
    }

    /** 编辑中的表单值 */
    data class Draft(
        val name: String = "",
        val nickname: String = "",
        val company: String = "",
        val department: String = "",
        val jobTitle: String = "",
        val phones: List<DraftItem> = emptyList(),
        val emails: List<DraftItem> = emptyList(),
        val addresses: List<DraftPostal> = emptyList(),
        val websites: List<DraftItem> = emptyList(),
        val birthday: String = "",
        val note: String = "",
        val starred: Boolean = false,
    )

    data class DraftItem(val value: String, val type: Int)

    data class DraftPostal(
        val street: String,
        val city: String,
        val region: String,
        val postcode: String,
        val type: Int,
    )

    sealed interface SaveResult {
        data class Ok(val contactId: Long, val changed: Boolean) : SaveResult
        data class Fail(val message: String) : SaveResult
    }

    // ------------------------------------------------------------------ 读

    fun load(context: Context, contactId: Long): Detail? = guard("load") {
        val head = context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.STARRED,
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() to (c.getInt(1) != 0) else null
        } ?: return@guard null

        var rawContactId = 0L
        var nickname = ""
        var company = ""
        var department = ""
        var jobTitle = ""
        var birthday = ""
        var note = ""
        val phones = ArrayList<Item>(2)
        val emails = ArrayList<Item>(1)
        val addresses = ArrayList<Postal>(1)
        val websites = ArrayList<Item>(1)

        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(
                Data._ID,
                Data.RAW_CONTACT_ID,
                Data.MIMETYPE,
                Data.DATA1,
                Data.DATA2,
                Data.DATA4,
                Data.DATA5,
                Data.DATA7,
                Data.DATA8,
                Data.DATA9,
            ),
            "${Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            val iId = c.getColumnIndex(Data._ID)
            val iRaw = c.getColumnIndex(Data.RAW_CONTACT_ID)
            val iMime = c.getColumnIndex(Data.MIMETYPE)
            val iD1 = c.getColumnIndex(Data.DATA1)
            val iD2 = c.getColumnIndex(Data.DATA2)
            val iD4 = c.getColumnIndex(Data.DATA4)
            val iD5 = c.getColumnIndex(Data.DATA5)
            val iD7 = c.getColumnIndex(Data.DATA7)
            val iD8 = c.getColumnIndex(Data.DATA8)
            val iD9 = c.getColumnIndex(Data.DATA9)

            fun s(idx: Int) = if (idx >= 0) c.getString(idx).orEmpty() else ""
            fun n(idx: Int) = if (idx >= 0) c.getInt(idx) else 0

            while (c.moveToNext()) {
                val dataId = c.getLong(iId)
                val rawId = c.getLong(iRaw)
                if (rawContactId == 0L) rawContactId = rawId
                val value1 = s(iD1)
                when (c.getString(iMime)) {
                    Nickname.CONTENT_ITEM_TYPE -> nickname = value1
                    Organization.CONTENT_ITEM_TYPE -> {
                        company = value1
                        jobTitle = s(iD4)
                        department = s(iD5)
                    }
                    Phone.CONTENT_ITEM_TYPE ->
                        if (value1.isNotBlank()) phones.add(Item(dataId, value1, n(iD2)))
                    Email.CONTENT_ITEM_TYPE ->
                        if (value1.isNotBlank()) emails.add(Item(dataId, value1, n(iD2)))
                    Website.CONTENT_ITEM_TYPE ->
                        if (value1.isNotBlank()) websites.add(Item(dataId, value1, n(iD2)))
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val p = Postal(
                            dataId = dataId,
                            street = s(iD4),
                            city = s(iD7),
                            region = s(iD8),
                            postcode = s(iD9),
                            type = n(iD2),
                        )
                        if (!p.isBlank) addresses.add(p)
                    }
                    // 生日只是 Event 的一种，纪念日之类的 TYPE 不用
                    MIME_EVENT -> if (n(iD2) == Event.TYPE_BIRTHDAY) birthday = value1
                    Note.CONTENT_ITEM_TYPE -> note = value1
                }
            }
        }

        Detail(
            contactId = contactId,
            rawContactId = rawContactId,
            name = head.first,
            starred = head.second,
            nickname = nickname,
            company = company,
            department = department,
            jobTitle = jobTitle,
            phones = phones,
            emails = emails,
            addresses = addresses,
            websites = websites,
            birthday = birthday,
            note = note,
        )
    }

    /**
     * 按号码反查联系人。
     *
     * 通话记录里只有号码和 cached_name，后者并不可靠（陌生号码为空，
     * 有些机型上甚至是过期数据），所以一律用 PhoneLookup 反查一次。
     */
    fun loadByNumber(context: Context, number: String): Detail? = guard("loadByNumber") {
        val digits = PhoneLocationDb.normalize(number) ?: return@guard null
        if (digits.length < 3) return@guard null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        val contactId = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@guard null
        load(context, contactId)
    }

    // ------------------------------------------------------------------ 写

    /**
     * 保存。[existing] 为空表示新建。
     *
     * 逐字段替换：认识的行更新/补插/删除，不认识的 mimetype 原样留着。
     */
    /**
     * 把号码追加到已有联系人 —— 拨号页「添加到联系人」用。
     *
     * 走的是「读全量快照 → 追加一行电话 → 整体写回」这条现成路径，
     * 而不是直接往 Data 表插一行：`save()` 已经处理好 raw contact 定位、
     * 逐 mimetype 替换和聚合等待，单独插行容易漏掉这些细节。
     */
    fun addPhone(
        context: Context,
        contactId: Long,
        number: String,
        type: Int = Phone.TYPE_MOBILE,
    ): SaveResult = guardResult("addPhone") {
        val clean = number.trim()
        if (clean.isEmpty()) return@guardResult SaveResult.Fail("号码为空")

        val detail = load(context, contactId)
            ?: return@guardResult SaveResult.Fail("联系人不存在，可能已被删除")

        // 同一个号码不要重复添加
        val exists = detail.phones.any { it.value.filter { c -> c.isDigit() } == clean.filter { c -> c.isDigit() } }
        if (exists) return@guardResult SaveResult.Fail("该联系人已经有这个号码")

        val draft = detail.toDraft().copy(
            phones = detail.toDraft().phones + DraftItem(clean, type)
        )
        save(context, detail, draft)
    }

    fun save(context: Context, existing: Detail?, draft: Draft): SaveResult {
        val cleanName = draft.name.trim()
        val hasNumber = draft.phones.any { it.value.isNotBlank() }
        if (cleanName.isEmpty() && !hasNumber && draft.nickname.isBlank() &&
            draft.company.isBlank() && draft.emails.none { it.value.isNotBlank() }
        ) {
            return SaveResult.Fail("姓名和号码至少要填一项")
        }
        return guardResult("save") {
            val contactId = if (existing == null) {
                create(context, draft)
                    ?: return@guardResult SaveResult.Fail("新建联系人失败，系统拒绝写入")
            } else {
                updateAll(context, existing, draft)
                existing.contactId
            }
            runCatching { setStarred(context, contactId, draft.starred) }
            Thread.sleep(AGGREGATE_SETTLE_MS)
            SaveResult.Ok(contactId, changed = true)
        }
    }

    /** 更新已有联系人：按 mimetype 分别处理 */
    private fun updateAll(context: Context, detail: Detail, draft: Draft) {
        val resolver = context.contentResolver
        val raw = detail.rawContactId
        val singleIds = singleDataIds(resolver, raw)

        // ---- 姓名 ----
        /*
         * 界面上只有一个「姓名」输入框（不对应原机的姓/名拆分），
         * 所以这里写 DISPLAY_NAME + GIVEN_NAME，并**必须把 FAMILY_NAME 等清空**。
         *
         * 踩过的坑：只写 DISPLAY_NAME/GIVEN_NAME、不动 FAMILY_NAME 时，
         * provider 会按「姓 + 名」重新拼 display name。原数据如果本来就
         * FAMILY_NAME="张三"、GIVEN_NAME 为空，保存后就会变成「张三张三」。
         */
        val shownName = draft.name.trim().ifEmpty {
            draft.phones.firstOrNull { it.value.isNotBlank() }?.value.orEmpty()
        }
        val nameValues = ContentValues().apply {
            put(StructuredName.DISPLAY_NAME, shownName)
            put(StructuredName.GIVEN_NAME, shownName)
            putNull(StructuredName.FAMILY_NAME)
            putNull(StructuredName.MIDDLE_NAME)
            putNull(StructuredName.PREFIX)
            putNull(StructuredName.SUFFIX)
        }
        upsertSingle(
            resolver, raw, StructuredName.CONTENT_ITEM_TYPE,
            singleIds[StructuredName.CONTENT_ITEM_TYPE], nameValues,
            hasContent = shownName.isNotEmpty()
        )

        // ---- 昵称 ----
        upsertSingle(
            resolver, raw, Nickname.CONTENT_ITEM_TYPE,
            singleIds[Nickname.CONTENT_ITEM_TYPE],
            ContentValues().apply { put(Nickname.NAME, draft.nickname.trim()) },
            hasContent = draft.nickname.isNotBlank()
        )

        // ---- 公司 / 部门 / 职位（同一行的三个列） ----
        val orgValues = ContentValues().apply {
            put(Organization.COMPANY, draft.company.trim())
            put(Organization.DEPARTMENT, draft.department.trim())
            put(Organization.TITLE, draft.jobTitle.trim())
        }
        upsertSingle(
            resolver, raw, Organization.CONTENT_ITEM_TYPE,
            singleIds[Organization.CONTENT_ITEM_TYPE], orgValues,
            hasContent = draft.company.isNotBlank() ||
                draft.department.isNotBlank() || draft.jobTitle.isNotBlank()
        )

        // ---- 生日 ----
        val birthValues = ContentValues().apply {
            put(Event.START_DATE, draft.birthday.trim())
            put(Event.TYPE, Event.TYPE_BIRTHDAY)
        }
        upsertSingle(
            resolver, raw, MIME_EVENT,
            singleIds[MIME_EVENT], birthValues,
            hasContent = draft.birthday.isNotBlank()
        )

        // ---- 备注 ----
        upsertSingle(
            resolver, raw, Note.CONTENT_ITEM_TYPE,
            singleIds[Note.CONTENT_ITEM_TYPE],
            ContentValues().apply { put(Note.NOTE, draft.note.trim()) },
            hasContent = draft.note.isNotBlank()
        )

        // ---- 多行字段 ----
        syncSimple(
            resolver, raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, Phone.TYPE,
            detail.phones, draft.phones
        )
        syncSimple(
            resolver, raw, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, Email.TYPE,
            detail.emails, draft.emails
        )
        syncSimple(
            resolver, raw, Website.CONTENT_ITEM_TYPE, Website.URL, Website.TYPE,
            detail.websites, draft.websites
        )
        syncPostal(resolver, raw, detail.addresses, draft.addresses)
    }

    /** 新建本机联系人，返回 contactId */
    private fun create(context: Context, draft: Draft): Long? {
        val resolver = context.contentResolver
        val rawUri = resolver.insert(
            RawContacts.CONTENT_URI,
            ContentValues().apply {
                put(RawContacts.ACCOUNT_TYPE, null as String?)
                put(RawContacts.ACCOUNT_NAME, null as String?)
            }
        ) ?: return null
        val rawId = ContentUris.parseId(rawUri)

        val inserted = runCatching {
            val shownName = draft.name.trim().ifEmpty {
                draft.phones.firstOrNull { it.value.isNotBlank() }?.value.orEmpty()
            }
            if (shownName.isNotEmpty()) {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        put(StructuredName.DISPLAY_NAME, shownName)
                        put(StructuredName.GIVEN_NAME, shownName)
                    }
                )
            }
            if (draft.nickname.isNotBlank()) {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                        put(Nickname.NAME, draft.nickname.trim())
                    }
                )
            }
            if (draft.company.isNotBlank() || draft.department.isNotBlank() ||
                draft.jobTitle.isNotBlank()
            ) {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                        put(Organization.COMPANY, draft.company.trim())
                        put(Organization.DEPARTMENT, draft.department.trim())
                        put(Organization.TITLE, draft.jobTitle.trim())
                    }
                )
            }
            if (draft.birthday.isNotBlank()) {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, MIME_EVENT)
                        put(Event.START_DATE, draft.birthday.trim())
                        put(Event.TYPE, Event.TYPE_BIRTHDAY)
                    }
                )
            }
            if (draft.note.isNotBlank()) {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                        put(Note.NOTE, draft.note.trim())
                    }
                )
            }
            syncSimple(resolver, rawId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, Phone.TYPE,
                emptyList(), draft.phones)
            syncSimple(resolver, rawId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, Email.TYPE,
                emptyList(), draft.emails)
            syncSimple(resolver, rawId, Website.CONTENT_ITEM_TYPE, Website.URL, Website.TYPE,
                emptyList(), draft.websites)
            syncPostal(resolver, rawId, emptyList(), draft.addresses)
        }

        // 中途失败就回滚，别在通讯录里留半个空联系人
        if (inserted.isFailure) {
            Log.w(TAG, "create failed, rolling back", inserted.exceptionOrNull())
            runCatching { resolver.delete(rawUri, null, null) }
            return null
        }

        // 由 raw contact 回头查聚合出来的 contactId
        fun contactIdOf(): Long? = resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        return contactIdOf() ?: run {
            Thread.sleep(AGGREGATE_SETTLE_MS)
            contactIdOf()
        }
    }

    /** 该 raw contact 下每个「单值类型」现有的 Data._ID */
    private fun singleDataIds(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
    ): Map<String, Long> = runCatching {
        val out = HashMap<String, Long>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE),
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = c.getLong(0)
            }
        }
        out
    }.getOrDefault(emptyMap())

    /**
     * 单值字段的写入：有就更新、没有就插、值被清空就删。
     *
     * [hasContent] 为 false 表示用户把这个字段清空了，此时应删掉整行 ——
     * 留一行空数据会让「界面没填」和「填了空串」两种状态对不上。
     */
    private fun upsertSingle(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
        mime: String,
        dataId: Long?,
        values: ContentValues,
        hasContent: Boolean,
    ) {
        when {
            !hasContent && dataId != null ->
                resolver.delete(Data.CONTENT_URI, "${Data._ID} = ?", arrayOf(dataId.toString()))
            !hasContent -> Unit
            dataId != null ->
                resolver.update(Data.CONTENT_URI, values, "${Data._ID} = ?", arrayOf(dataId.toString()))
            else -> {
                values.put(Data.RAW_CONTACT_ID, rawContactId)
                values.put(Data.MIMETYPE, mime)
                resolver.insert(Data.CONTENT_URI, values)
            }
        }
    }

    /**
     * 多行字段（电话 / 邮箱 / 网站）的行级替换。
     *
     * 按下标一一对应：前 N 行更新、多出来的插、界面删掉的行删掉。
     * 空值一律不落库。
     */
    private fun syncSimple(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
        mime: String,
        valueColumn: String,
        typeColumn: String,
        existing: List<Item>,
        desired: List<DraftItem>,
    ) {
        val clean = desired.filter { it.value.isNotBlank() }
        clean.forEachIndexed { i, d ->
            if (i < existing.size) {
                resolver.update(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(valueColumn, d.value.trim())
                        put(typeColumn, d.type)
                    },
                    "${Data._ID} = ?", arrayOf(existing[i].dataId.toString())
                )
            } else {
                resolver.insert(
                    Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawContactId)
                        put(Data.MIMETYPE, mime)
                        put(valueColumn, d.value.trim())
                        put(typeColumn, d.type)
                    }
                )
            }
        }
        for (i in clean.size until existing.size) {
            resolver.delete(
                Data.CONTENT_URI, "${Data._ID} = ?",
                arrayOf(existing[i].dataId.toString())
            )
        }
    }

    /** 地址行替换，四个列一起写 */
    private fun syncPostal(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
        existing: List<Postal>,
        desired: List<DraftPostal>,
    ) {
        val clean = desired.filter { !it.isBlank }
        clean.forEachIndexed { i, d ->
            val values = ContentValues().apply {
                put(StructuredPostal.STREET, d.street.trim())
                put(StructuredPostal.CITY, d.city.trim())
                put(StructuredPostal.REGION, d.region.trim())
                put(StructuredPostal.POSTCODE, d.postcode.trim())
                put(StructuredPostal.TYPE, d.type)
            }
            if (i < existing.size) {
                resolver.update(
                    Data.CONTENT_URI, values,
                    "${Data._ID} = ?", arrayOf(existing[i].dataId.toString())
                )
            } else {
                values.put(Data.RAW_CONTACT_ID, rawContactId)
                values.put(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                resolver.insert(Data.CONTENT_URI, values)
            }
        }
        for (i in clean.size until existing.size) {
            resolver.delete(
                Data.CONTENT_URI, "${Data._ID} = ?",
                arrayOf(existing[i].dataId.toString())
            )
        }
    }

    private val DraftPostal.isBlank: Boolean
        get() = street.isBlank() && city.isBlank() && region.isBlank() && postcode.isBlank()

    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean =
        guard("setStarred") {
            context.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                },
                null, null
            ) > 0
        } ?: false

    fun delete(context: Context, contactId: Long): SaveResult = guardResult("delete") {
        val removed = context.contentResolver.delete(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            null, null
        )
        Thread.sleep(AGGREGATE_SETTLE_MS)
        if (removed > 0) SaveResult.Ok(contactId, changed = true)
        else SaveResult.Fail("删除失败，联系人可能已不存在")
    }

    // ------------------------------------------------------------------ 工具

    private inline fun <T> guard(tag: String, block: () -> T?): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "$tag: no permission", e)
        null
    } catch (e: Exception) {
        Log.w(TAG, "$tag failed", e)
        null
    }

    private inline fun guardResult(tag: String, block: () -> SaveResult): SaveResult = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "$tag: no permission", e)
        SaveResult.Fail("缺少「修改联系人」权限，请先到权限设置里授予")
    } catch (e: Exception) {
        Log.w(TAG, "$tag failed", e)
        SaveResult.Fail("操作失败：${e.javaClass.simpleName}")
    }
}
