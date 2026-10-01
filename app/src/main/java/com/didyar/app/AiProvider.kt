package com.didyar.app

import android.content.Context

enum class AiProvider(
    val id: String,
    val displayName: String,
    val secretName: String,
    val modelId: String,
    val endpoints: List<String>
) {
    AVALAI(
        id = "avalai",
        displayName = "AvalAI",
        secretName = "avalai_api_key",
        modelId = "gemini-3.8-flash",
        endpoints = listOf(
            "https://api.avalai.ir/v1/chat/completions",
            "https://api.avalapis.ir/v1/chat/completions",
            "https://api.avalai.org/v1/chat/completions"
        )
    ),
    NETARZ(
        id = "netarz",
        displayName = "نِت‌اَرز",
        secretName = "netarz_api_key",
        modelId = "gemini-3.8-flash",
        endpoints = listOf(
            "https://netarz.ir/api/ai/v1/chat/completions"
        )
    );

    companion object {
        fun fromId(id: String?): AiProvider =
            entries.firstOrNull { it.id == id } ?: AVALAI
    }
}

object AiProviderStore {
    private const val PREFS = "didyar_ai_provider"
    private const val KEY_PROVIDER = "provider"

    fun load(context: Context): AiProvider {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER, AiProvider.AVALAI.id)
        return AiProvider.fromId(id)
    }

    fun save(context: Context, provider: AiProvider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER, provider.id)
            .apply()
    }
}
