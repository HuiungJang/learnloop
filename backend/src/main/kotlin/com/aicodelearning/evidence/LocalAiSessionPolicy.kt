package com.aicodelearning.evidence

object LocalAiSessionPolicy {
    const val SOURCE_KIND = "local_ai_session"
    const val STATUS_GENERATION_ELIGIBLE = "generation_eligible"
    const val USER_ATTRIBUTION_USE_FOR_GENERATION = "use_for_generation"
    const val USER_ATTRIBUTION_MANUAL = "manual"
    const val USER_ATTRIBUTION_DELETE = "delete"
    const val ITEM_TYPE_PROMPT = "prompt"
    const val ITEM_TYPE_AI_RESPONSE = "ai_response"
    const val ITEM_TYPE_FILE_BEFORE = "file_before"
    const val ITEM_TYPE_FILE_AFTER = "file_after"
    const val ITEM_TYPE_DIFF = "diff"
    const val ITEM_TYPE_TOOL_EVENT = "tool_event"

    val userAttributions = setOf(USER_ATTRIBUTION_USE_FOR_GENERATION, USER_ATTRIBUTION_MANUAL, USER_ATTRIBUTION_DELETE)
    val itemTypes = setOf(ITEM_TYPE_PROMPT, ITEM_TYPE_AI_RESPONSE, ITEM_TYPE_FILE_BEFORE, ITEM_TYPE_FILE_AFTER, ITEM_TYPE_DIFF, ITEM_TYPE_TOOL_EVENT)
    val generationItemTypes = setOf(ITEM_TYPE_PROMPT, ITEM_TYPE_AI_RESPONSE, ITEM_TYPE_FILE_BEFORE, ITEM_TYPE_FILE_AFTER, ITEM_TYPE_DIFF)
    val itemTypesRequiringPath = setOf(ITEM_TYPE_FILE_BEFORE, ITEM_TYPE_FILE_AFTER, ITEM_TYPE_DIFF)
    val itemTypeOrder =
        mapOf(
            ITEM_TYPE_PROMPT to 0,
            ITEM_TYPE_AI_RESPONSE to 1,
            ITEM_TYPE_FILE_BEFORE to 2,
            ITEM_TYPE_FILE_AFTER to 3,
            ITEM_TYPE_DIFF to 4,
            ITEM_TYPE_TOOL_EVENT to 5,
        )

    fun isDiff(itemType: String): Boolean = itemType == ITEM_TYPE_DIFF

    fun requiresGenerationContent(itemType: String): Boolean = itemType in generationItemTypes

    fun itemSortRank(itemType: String): Int = itemTypeOrder[itemType] ?: Int.MAX_VALUE
}
