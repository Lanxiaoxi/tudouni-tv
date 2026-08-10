package com.tudouni.tv.data

/**
 * 内容分级过滤：敏感关键词定义和过滤逻辑。
 * 与 Web 端保持一致（16 个敏感词）。
 */
object ContentFilter {
    
    // 敏感关键词列表（与 Web 端保持一致）
    private val BANNED_KEYWORDS = listOf(
        "伦理片", "福利", "里番动漫", "门事件", "萝莉少女",
        "制服诱惑", "国产传媒", "cosplay", "黑丝诱惑",
        "无码", "日本无码", "有码", "日本有码", "SWAG",
        "网红主播", "色情片", "同性片", "福利视频", "福利片"
    )
    
    /**
     * 检查视频是否应该被过滤（包含敏感关键词）。
     * @param typeName 视频类型名称（type_name）
     * @return true 表示应该过滤（隐藏），false 表示安全
     */
    fun isSensitiveContent(typeName: String?): Boolean {
        if (typeName.isNullOrBlank()) return false
        return BANNED_KEYWORDS.any { keyword ->
            typeName.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * 过滤视频列表，移除敏感内容。
     * @param items 原始视频列表
     * @return 过滤后的视频列表
     */
    fun filterItems(items: List<VideoItem>): List<VideoItem> {
        return items.filter { !isSensitiveContent(it.typeName) }
    }
    
    /**
     * 获取所有敏感关键词（用于显示或调试）。
     */
    fun getBannedKeywords(): List<String> = BANNED_KEYWORDS.toList()
    
    /**
     * 获取敏感关键词数量。
     */
    fun getBannedKeywordsCount(): Int = BANNED_KEYWORDS.size
}
