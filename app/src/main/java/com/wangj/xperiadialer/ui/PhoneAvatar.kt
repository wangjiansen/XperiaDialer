package com.wangj.xperiadialer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.wangj.xperiadialer.data.PhoneBook
import com.wangj.xperiadialer.data.PhoneLocationDb

/**
 * 带黄页 logo 的头像：号码命中黄页就显示机构图标，否则回落到 [LetterAvatar]。
 *
 * ## 什么时候**不**显示 logo
 *
 * 只有当显示的名字来自黄页（或压根没有名字）时才用 logo。
 * 如果 `name` 非空且和黄页机构名不同，说明这个名字是通讯录里解析出来的真人姓名，
 * 这时保留字母头像 —— 否则通讯录里存了「顺丰王师傅」而号码恰好是顺丰客服号，
 * 就会把一个人显示成企业的 logo。
 *
 * ## 黄页数据不写通讯录
 *
 * logo 和号码都只从 `assets/` 读，不查也不写系统 ContactsProvider。
 * 和真实联系人完全是两条链路。
 */
@Composable
fun PhoneAvatar(
    name: String,
    number: String,
    colorKey: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val logo: ImageBitmap? = remember(number, name) {
        val digits = PhoneLocationDb.normalize(number)
        if (digits.isNullOrEmpty()) return@remember null

        val entry = PhoneBook.find(context, digits) ?: return@remember null
        if (entry.logo.isEmpty()) return@remember null
        if (name.isNotBlank() && name.trim() != entry.name) return@remember null

        PhoneBook.loadLogo(context, entry.logo)?.asImageBitmap()
    }

    if (logo != null) {
        Image(
            bitmap = logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        LetterAvatar(
            name = name,
            number = number,
            colorKey = colorKey,
            size = size,
            modifier = modifier,
        )
    }
}
