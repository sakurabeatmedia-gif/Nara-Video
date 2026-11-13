package com.example.data.api

import android.content.Context
import android.content.SharedPreferences

object ApiConstants {
    const val API_BASE_URL = "https://apishort.com/v1/melolo/api/"
    const val API_KEY = "vbs_cbb6e561d49018a8ed3f1f55bfd0e6662f157f753f7ca08a"
    const val HEADER_API_KEY = "X-API-Key"
    const val HEADER_ACCEPT = "Accept"
    const val ACCEPT_JSON = "application/json"
    const val DEFAULT_LANG = "id"

    val API_HEADERS: Map<String, String> = mapOf(
        HEADER_API_KEY to API_KEY,
        HEADER_ACCEPT to ACCEPT_JSON
    )
}

object ApiKeyStore {
    private const val PREFS_NAME = "nara_api_prefs"
    private const val KEY_CUSTOM_API_KEY = "custom_api_key"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun getCustomApiKey(): String {
        return prefs?.getString(KEY_CUSTOM_API_KEY, "")?.trim().orEmpty()
    }

    fun hasCustomApiKey(): Boolean = getCustomApiKey().isNotEmpty()

    fun getActiveApiKey(): String {
        val custom = getCustomApiKey()
        return custom.ifEmpty { ApiConstants.API_KEY }
    }

    fun getMaskedCustomApiKey(): String {
        val custom = getCustomApiKey()
        if (custom.isEmpty()) return ""
        return if (custom.length <= 8) {
            "••••••••"
        } else {
            "${custom.take(4)}••••••••${custom.takeLast(4)}"
        }
    }

    fun saveCustomApiKey(apiKey: String) {
        prefs?.edit()?.putString(KEY_CUSTOM_API_KEY, apiKey.trim())?.apply()
    }

    fun clearCustomApiKey() {
        prefs?.edit()?.remove(KEY_CUSTOM_API_KEY)?.apply()
    }
}
