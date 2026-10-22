package com.wangj.xperiadialer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 默认头像：圆形底 + 白色人形剪影。
 *
 * 对齐原机的占位样式 —— 联系人没有照片时，卡片和编辑页都显示这个。
 * 纯 Canvas 画，不依赖图片资源，换尺寸也不会糊。
 *
 * [colorKey] 传号码时用 [SonyColors.avatarColor] 取色（和列表里的字母头像同一套
 * 调色板），同一个人在各处颜色一致；不传则是原机那种中性灰。
 */
@Composable
fun DefaultAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    colorKey: String? = null,
    backgroundColor: Color = if (colorKey.isNullOrBlank()) {
        Color(0xFFBDBDBD)
    } else {
        SonyColors.avatarColor(colorKey)
    },
    silhouetteColor: Color = Color.White,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
    ) {
        val w = this.size.width
        val h = this.size.height
        // 头
        drawCircle(
            color = silhouetteColor,
            radius = w * 0.155f,
            center = Offset(w / 2f, h * 0.335f)
        )
        // 肩：圆心压在底边附近，用大半径的圆近似宽肩，超出画布的部分被裁掉
        drawCircle(
            color = silhouetteColor,
            radius = w * 0.42f,
            center = Offset(w / 2f, h * 0.95f)
        )
    }
}
