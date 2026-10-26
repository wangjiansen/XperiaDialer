package com.wangj.xperiadialer.data

import android.content.Context

/** 来电风险等级 */
enum class SpamLevel {
    /** 明确安全：用户白名单、公共服务号 */
    SAFE,

    /** 普通号码，无已知风险 */
    UNKNOWN,

    /** 需要注意：企业客服、虚拟运营商、国际来电 */
    CAUTION,

    /** 骚扰：用户拉黑、声讯台 */
    SPAM,
}

/**
 * 一次来电筛查的结论。
 *
 * @param label 界面上显示的短标签，如「企业客服」「虚拟运营商」；UNKNOWN 时为空
 * @param reason 命中原因，用于调试和后续「为什么标记它」的说明
 * @param block 是否拦截。本轮只对用户手动拉黑的号码生效，规则命中的一律只提示
 */
data class SpamVerdict(
    val level: SpamLevel,
    val label: String = "",
    val reason: String = "",
    val block: Boolean = false,
    val location: PhoneLocationDb.Info? = null,
) {
    val hasLabel: Boolean get() = label.isNotBlank()

    companion object {
        fun unknown(location: PhoneLocationDb.Info? = null) =
            SpamVerdict(SpamLevel.UNKNOWN, location = location)
    }
}

/**
 * 本地骚扰识别。
 *
 * 纯本地、同步、无网络 —— CallScreeningService 只有 5 秒响应窗口，
 * 超时系统会直接解绑且手机不响铃，所以这里绝不能做任何 IO 阻塞。
 * 归属地为空时降级为纯号段判断，不影响整体流程。
 *
 * 判定优先级（先命中先返回）：
 *   1. 用户黑名单  → 拦截
 *   2. 用户白名单  → 放行
 *   3. 黄页号码库  → 显示机构名（600+ 条，来自 metowolf/vCards）
 *   4. 内置服务号  → 显示机构名（黄页缺失时的兜底）
 *   5. 号段规则    → 提示（不拦截）
 *   6. 普通号码    → 仅展示归属地
 */
object SpamAnalyzer {

    /**
     * 已知服务号码（银行 / 快递 / 航空 / 保险 / 政务等）。
     * 命中后直接显示具体机构名，例如「招商银行」「顺丰速运」，
     * 而不是笼统的「全国客服」—— 这是国产 ROM 来电黄页的核心体验。
     */
    private val PUBLIC_SERVICE = setOf(
        "110", "119", "120", "122", "12345", "12306", "12123",
    )

    /** 声讯台 / 高资费号段 */
    private val PREMIUM_RATE_PREFIX = listOf("12590", "12586", "1258", "168")

    /** 虚拟运营商号段，骚扰与营销电话占比偏高 */
    private val VIRTUAL_CARRIER_PREFIX = listOf("170", "171", "162", "165", "167")

    fun analyze(context: Context, rawNumber: String): SpamVerdict {
        val digits = PhoneLocationDb.normalize(rawNumber)
        val location = runCatching { PhoneLocationDb.lookup(context, rawNumber) }.getOrNull()

        if (digits == null) return SpamVerdict.unknown(location)

        // 1. 用户标记优先，用户自己的判断永远最大
        when (SpamStore.getMark(context, digits)) {
            SpamStore.MARK_BLACK_LABEL ->
                return SpamVerdict(SpamLevel.SPAM, "已拉黑", "用户标记", block = true, location = location)
            SpamStore.MARK_WHITE_LABEL ->
                return SpamVerdict(SpamLevel.SAFE, "已信任", "用户标记", location = location)
            else -> Unit
        }

        // 2. 黄页号码库：600+ 条企业/机构号码，覆盖银行、快递、电商、互联网、证券等
        //    命中后直接显示机构名，这是国产 ROM 来电黄页的核心体验
        PhoneBook.find(context, digits)?.let { entry ->
            return SpamVerdict(
                level = SpamLevel.SAFE,
                label = entry.name,
                reason = entry.category,
                location = location,
            )
        }

        // 3. 内置服务号表：黄页数据缺失或未收录时的兜底
        KnownNumbers.find(digits)?.let { entry ->
            return SpamVerdict(
                level = SpamLevel.SAFE,
                label = entry.name,
                reason = entry.category,
                location = location,
            )
        }

        // 4. 内置应急号码
        if (digits in PUBLIC_SERVICE) {
            return SpamVerdict(SpamLevel.SAFE, "公共服务", "内置服务号", location = location)
        }

        // 5. 国际 / 非中国大陆号码
        val isInternational = rawNumber.trimStart().startsWith("+") || digits.startsWith("00")
        if (isInternational) {
            return SpamVerdict(SpamLevel.CAUTION, "国际来电", "非中国大陆号码", location = location)
        }

        // 6. 号段规则
        matchSegmentRule(digits)?.let { (level, label, reason) ->
            return SpamVerdict(level, label, reason, location = location)
        }

        // 7. 普通号码，只带归属地
        return SpamVerdict(SpamLevel.UNKNOWN, location = location)
    }

    private fun matchSegmentRule(digits: String): Triple<SpamLevel, String, String>? {
        // 400 / 800 企业热线：10 位
        if (digits.length == 10) {
            if (digits.startsWith("400")) {
                return Triple(SpamLevel.CAUTION, "企业客服", "400 号段")
            }
            if (digits.startsWith("800")) {
                return Triple(SpamLevel.CAUTION, "企业客服", "800 号段")
            }
        }
        // 1010 企业客服
        if (digits.startsWith("1010") && digits.length in 8..12) {
            return Triple(SpamLevel.CAUTION, "企业客服", "1010 号段")
        }
        // 5 位短号：95xxx 全国客服、96xxx 声讯客服
        if (digits.length == 5 && digits.startsWith("95")) {
            return Triple(SpamLevel.CAUTION, "全国客服", "95 号段")
        }
        if (digits.length == 5 && digits.startsWith("96")) {
            return Triple(SpamLevel.CAUTION, "声讯客服", "96 号段")
        }
        // 高资费声讯台
        if (PREMIUM_RATE_PREFIX.any { digits.startsWith(it) }) {
            return Triple(SpamLevel.SPAM, "声讯台", "高资费号段")
        }
        // 虚拟运营商
        if (digits.length == 11 && VIRTUAL_CARRIER_PREFIX.any { digits.startsWith(it) }) {
            return Triple(SpamLevel.CAUTION, "虚拟运营商", "虚商号段")
        }
        return null
    }
}
