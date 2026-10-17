package com.jhaiian.clint.browser.webview

import android.content.Context
import android.webkit.WebSettings
import androidx.preference.PreferenceManager
import androidx.webkit.UserAgentMetadata

object GenericUserAgent {

    private const val ANDROID_VERSION = "10"
    private const val PLATFORM_VERSION = "10.0.0"
    private const val MODEL = "K"
    private const val FALLBACK_MAJOR = "134"

    fun majorVersion(defaultUserAgent: String): String =
        Regex("Chrome/(\\d+)").find(defaultUserAgent)?.groupValues?.get(1) ?: FALLBACK_MAJOR

    fun mobile(defaultUserAgent: String): String {
        val major = majorVersion(defaultUserAgent)
        return "Mozilla/5.0 (Linux; Android $ANDROID_VERSION; $MODEL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    fun resolve(context: Context): String {
        val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
        val enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("custom_user_agent", true)
        return if (enabled) mobile(defaultUserAgent) else defaultUserAgent
    }

    fun metadata(defaultUserAgent: String): UserAgentMetadata {
        val major = majorVersion(defaultUserAgent)
        val full = "$major.0.0.0"
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not-A.Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build()
        )
        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(full)
            .setMobile(true)
            .setPlatform("Android")
            .setPlatformVersion(PLATFORM_VERSION)
            .setModel(MODEL)
            .build()
    }
}
