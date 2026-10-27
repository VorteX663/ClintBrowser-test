package com.jhaiian.clint.browser.delegates

import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import androidx.lifecycle.lifecycleScope
import com.jhaiian.clint.R
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.browser.home.isClintHomeUrl
import com.jhaiian.clint.browser.siteinfo.SiteInfoCertificate
import com.jhaiian.clint.browser.siteinfo.SiteInfoPermission
import com.jhaiian.clint.browser.siteinfo.SiteInfoRequest
import com.jhaiian.clint.browser.webview.ClintWebViewClient
import com.jhaiian.clint.settings.SettingsActivity
import com.jhaiian.clint.settings.sitepermissions.SitePermissionDatabase
import com.jhaiian.clint.settings.sitepermissions.SitePermissionManager
import com.jhaiian.clint.ui.showClintSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SiteInfoPermissionTypes = listOf(
    SitePermissionDatabase.TYPE_CAMERA,
    SitePermissionDatabase.TYPE_MIC,
    SitePermissionDatabase.TYPE_LOCATION,
    SitePermissionDatabase.TYPE_NOTIFICATION,
    SitePermissionDatabase.TYPE_CLIPBOARD
)

internal fun MainActivity.openSiteInfo(): Boolean {
    val tab = tabManager.activeTab ?: return false
    val webView = tab.webView
    val url = webView.url?.takeUnless { isClintHomeUrl(it) } ?: return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host ?: return false
    val certificate = if (scheme == "https") webView.certificate?.let(::siteInfoCertificate) else null
    val isSecure = scheme == "https"
    val isCertificateException = isSecure && (webView.webViewClient as? ClintWebViewClient)?.hasSslException(host) == true
    val canManageCookies = !tab.isIncognito

    lifecycleScope.launch {
        val permissions = withContext(Dispatchers.IO) { loadSiteInfoPermissions(host) }
        uiState.siteInfoRequest = SiteInfoRequest(
            url = url,
            host = host,
            isSecure = isSecure && !isCertificateException,
            isCertificateException = isCertificateException,
            certificate = certificate,
            permissions = permissions,
            canManageCookies = canManageCookies
        )
    }
    return true
}

internal fun MainActivity.changeSiteInfoPermission(host: String, type: String, state: String?) {
    lifecycleScope.launch {
        val permissions = withContext(Dispatchers.IO) {
            if (state == null) {
                SitePermissionManager.deleteEntry(this@changeSiteInfoPermission, host, type)
            } else {
                SitePermissionManager.setState(this@changeSiteInfoPermission, host, type, state)
            }
            loadSiteInfoPermissions(host)
        }
        uiState.siteInfoRequest?.takeIf { it.host == host }?.let { uiState.siteInfoRequest = it.copy(permissions = permissions) }
    }
}

internal fun MainActivity.resetSiteInfoPermissions(host: String) {
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            SiteInfoPermissionTypes.forEach { SitePermissionManager.deleteEntry(this@resetSiteInfoPermissions, host, it) }
        }
        uiState.siteInfoRequest?.takeIf { it.host == host }?.let { uiState.siteInfoRequest = it.copy(permissions = emptyList()) }
        showClintSnackbar(getString(R.string.site_info_permissions_reset, host.removePrefix("www.")))
    }
}

internal fun MainActivity.openSiteInfoCookies() {
    uiState.siteInfoRequest = null
    openCookiesControl()
}

internal fun MainActivity.openSiteInfoSiteSettings() {
    uiState.siteInfoRequest = null
    startActivity(
        Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_OPEN_FRAGMENT, "site_settings")
    )
}

private fun MainActivity.loadSiteInfoPermissions(host: String): List<SiteInfoPermission> =
    SiteInfoPermissionTypes.mapNotNull { type ->
        when (val state = SitePermissionManager.getState(this, host, type)) {
            SitePermissionDatabase.STATE_ALLOW, SitePermissionDatabase.STATE_DENY -> SiteInfoPermission(type, state)
            else -> null
        }
    }

private fun siteInfoCertificate(certificate: SslCertificate): SiteInfoCertificate =
    SiteInfoCertificate(
        issuedTo = certificate.issuedTo?.let { it.cName?.takeIf(String::isNotBlank) ?: it.oName?.takeIf(String::isNotBlank) },
        issuedBy = certificate.issuedBy?.let { it.oName?.takeIf(String::isNotBlank) ?: it.cName?.takeIf(String::isNotBlank) },
        validFrom = runCatching { certificate.validNotBeforeDate?.time ?: 0L }.getOrDefault(0L),
        validUntil = runCatching { certificate.validNotAfterDate?.time ?: 0L }.getOrDefault(0L)
    )
