package com.wangj.xperiadialer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 编辑页的挂载点。
 *
 * 编辑页原本是独立的 `Dialog` 窗口，和列表页不在同一个 window 里 ——
 * Compose 在 Dialog 里读不到 insets（status / nav / ime 全是 0），
 * 内容会一直画到导航栏底下，只能靠外部把高度传进来打补丁。
 * 改成 Activity 内的全屏覆盖层后，它和列表页共用一个 window，insets 天然有效。
 *
 * 但打开它的入口在联系人卡片里，而卡片渲染在 `ContactsScreen` / `CallLogScreen` 内部，
 * 那些内容被 `HomeScaffold` 的 `weight(1f)` 容器约束着，塞不下全屏覆盖层。
 * 所以用这个 controller 做中间人：卡片只管发请求，真正渲染在 HomeScaffold 的根 Box 里。
 */
object ContactEditorController {

    var request: ContactEditorRequest? by mutableStateOf(null)
        private set

    private var onSaved: ((String) -> Unit)? = null
    private var onFailed: ((String) -> Unit)? = null

    fun open(
        request: ContactEditorRequest,
        onSaved: (String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        this.request = request
        this.onSaved = onSaved
        this.onFailed = onFailed
    }

    fun close() {
        request = null
        onSaved = null
        onFailed = null
    }

    /** 保存成功：先关页再回调，避免回调里重新打开卡片时被这次关闭覆盖 */
    fun notifySaved(message: String) {
        val callback = onSaved
        close()
        callback?.invoke(message)
    }

    /** 保存失败：留在表单上让用户改，不关页 */
    fun notifyFailed(message: String) {
        onFailed?.invoke(message)
    }
}
