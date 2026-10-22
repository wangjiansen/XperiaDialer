package com.wangj.xperiadialer.data

import android.icu.text.Transliterator
import android.util.Log

/**
 * T9 拼音索引。
 *
 * 拨号盘上的数字键映射到字母（2=ABC … 9=WXYZ），把联系人姓名的**拼音**
 * 转成同样的数字串之后，输入 9426 就能搜到「张三」（zhangsan → 94264726）。
 *
 * 拼音来自系统自带的 ICU 汉字-拉丁转换器，不需要任何第三方库、不增加包体：
 *
 * ```
 * Transliterator.getInstance("Han-Latin; Latin-ASCII")
 * 张三 → "zhang san" → 去掉声调与空格 → "zhangsan"
 * ```
 *
 * `Latin-ASCII` 这一步很关键：`Han-Latin` 单独用会输出带声调的 `zhāng`，
 * 串上 `Latin-ASCII` 就能一步把声调符号压平成普通字母，比用
 * `Normalizer.NFD` 去掉组合字符更简单。
 */
object T9Engine {

    private const val TAG = "T9Engine"

    /**
     * 字母 → 数字键。下标是 `ch - 'a'`，值是数字字符编码。
     *
     * ```
     * abc→2  def→3  ghi→4  jkl→5  mno→6  pqrs→7  tuv→8  wxyz→9
     * ```
     */
    private val LETTER_TO_DIGIT = IntArray(26) { i ->
        when (i) {
            0, 1, 2 -> 2          // a b c
            3, 4, 5 -> 3          // d e f
            6, 7, 8 -> 4          // g h i
            9, 10, 11 -> 5        // j k l
            12, 13, 14 -> 6       // m n o
            15, 16, 17, 18 -> 7   // p q r s
            19, 20, 21 -> 8       // t u v
            else -> 9             // w x y z
        }
    }

    /**
     * ICU 转换器。构造开销不小，进程内只建一次。
     *
     * `Transliterator.transliterate()` 带内部状态，不是线程安全的，
     * 所有调用都用 [hanToLatin] 自身作为锁。首次初始化失败（个别 ROM 裁掉了
     * ICU 数据）时返回 null，调用方降级到 GBK 首字母。
     */
    private val hanToLatin: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        }.onFailure {
            Log.w(TAG, "ICU Han-Latin unavailable, T9 pinyin disabled", it)
        }.getOrNull()
    }

    /** 拼音转换是否可用（不可用时只做号码匹配） */
    val available: Boolean get() = hanToLatin != null

    /**
     * 文本 → 音节列表（全小写纯字母）。
     *
     * ICU 输出里汉字之间带空格，正好可以当音节分隔符：
     * 「张三丰」→ `["zhang", "san", "feng"]`；英文名 `Alice` → `["alice"]`。
     */
    fun toSyllables(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val t = hanToLatin ?: return emptyList()
        val raw = synchronized(t) {
            runCatching { t.transliterate(text) }.getOrNull()
        } ?: return emptyList()
        // 只留字母数字和空格，其余（声调残留、标点、连字符）当分隔
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch in 'a'..'z' -> append(ch)
                    ch in 'A'..'Z' -> append(ch.lowercaseChar())
                    ch in '0'..'9' -> append(ch)
                    else -> append(' ')
                }
            }
        }
        return cleaned.split(' ').filter { it.isNotEmpty() }
    }

    /** 文本 → 连写拼音。`张三` → `"zhangsan"` */
    fun toPinyin(text: String): String = toSyllables(text).joinToString("")

    /** 文本 → 各音节首字母，连写。`张三丰` → `"zsf"` */
    fun toInitials(text: String): String =
        toSyllables(text).mapNotNull { it.firstOrNull() }.joinToString("")

    /** 拼音串 → T9 数字串。`zhangsan` → `"94264726"` */
    fun toDigits(pinyin: String): String = buildString(pinyin.length) {
        for (ch in pinyin) {
            when {
                ch in 'a'..'z' -> append('0' + LETTER_TO_DIGIT[ch - 'a'])
                ch in '0'..'9' -> append(ch)
                // 其他字符（空格、标点）跳过
            }
        }
    }

    /** 便捷方法：文本直接转 T9 全拼数字串 */
    fun pinyinToT9(text: String): String = toDigits(toPinyin(text))

    /** 便捷方法：文本直接转 T9 首字母数字串 */
    fun initialsToT9(text: String): String = toDigits(toInitials(text))
}
