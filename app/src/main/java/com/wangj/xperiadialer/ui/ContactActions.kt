package com.wangj.xperiadialer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * 列表项上的「附加动作」：发短信、复制号码。
 *
 * 对齐 Sony 原机的分层交互：
 * - 点头像 → 联系人卡片（拨号/短信/收藏/编辑）
 * - 点行  → 就地展开短信、复制号码
 * - 点电话图标 → 直接拨号（由调用方处理）
 *
 * ## 关于 openCard 的现状（2026-10-21 更新）
 *
 * 联系人卡片已经改为自研（[ContactCardSheet]），不再走系统。这里保留
 * [openCard] 只作为「在系统通讯录中打开」的备用实现，列表里没有调用。
 *
 * 系统 QuickContact 在本机不可用的排查结论：发出的 Intent 是合规的
 * `ACTION_VIEW + content://com.android.contacts/contacts/lookup/<key>/<id>`，
 * 系统日志能看到 QuickContactActivity 被正常拉起（result code=0、
 * isTopActivityTransparent=true），但它启动后立刻自己 finish，卡片出不来。
 * 已排除：URI 格式（id 形式 → lookup 形式都试过）、包名歧义（已 setPackage）、
 * 包可见性、`ACTION_QUICK_CONTACT`（这台机器上无人响应）。
 *
 * 自研方案顺带解决了另一个问题：Sony 的编辑页同样打不开，而直接读写
 * ContactsProvider 在哪个 ROM 上都成立，见 [com.wangj.xperiadialer.data.ContactsWriter]。
 */
object ContactActions {

    private const val TAG = "ContactActions"

    /**
     * 打开系统的快捷联系人卡片。
     *
     * [contactUri] 是联系人的 content URI（通讯录用 `Contacts.CONTENT_URI + id`，
     * 通话记录直接用 `CallLog.CACHED_LOOKUP_URI`）。陌生号码没有 URI，此时什么也不做。
     *
     * 必须**先指定通讯录包名**：这台机器上能响应
     * `ACTION_VIEW + vnd.android.cursor.item/contact` 的有 4 个 App
     * （Sony 通讯录、移动的和通讯录等），不指定包名会弹出「选择应用」。
     */
    fun openCard(context: Context, contactUri: String?) {
        Log.i(TAG, "openCard uri=$contactUri")
        if (contactUri.isNullOrBlank()) return
        val base = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(contactUri)
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 优先 Sony 原机通讯录，拿不到再退回通用 Intent
        val preferred = Intent(base).setPackage(SONY_CONTACTS_PACKAGE)
        if (start(context, preferred, "openCard")) return
        start(context, base, "openCard(fallback)")
    }

    private const val SONY_CONTACTS_PACKAGE = "com.sonymobile.android.contacts"

    /** 发短信：只带号码，正文留给短信 App */
    fun sendSms(context: Context, number: String) {
        if (number.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, intent, "sendSms")
    }

    /** 复制到剪贴板 */
    fun copyNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("电话号码", number))
    }

    /** @return 是否成功启程 */
    private fun start(context: Context, intent: Intent, tag: String): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$tag: no activity for ${intent.action}/${intent.type}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "$tag: security exception", e)
            false
        }
    }
}
