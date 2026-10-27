package com.jhaiian.clint.settings.privacy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PrivacySettingsUiState(
    initialBlockThirdPartyCookies: Boolean,
    initialCustomUserAgent: Boolean,
    initialHttpsOnly: Boolean,
    initialDoNotTrack: Boolean,
    initialStripTrackingParams: Boolean,
    initialGlobalPrivacyControl: Boolean,
    initialWebRtcProtection: Boolean,
    initialClearOnExit: Boolean,
    initialClearHistory: Boolean,
    initialClearCookies: Boolean,
    initialClearCache: Boolean,
    initialClearTabs: Boolean,
    initialAppLock: Boolean,
    initialAppLockTimeout: Int,
    initialScreenshotBlocking: Boolean,
    initialIncognitoKeyboard: Boolean,
    initialClipboardClearSeconds: Int,
    val hideStatusBar: Boolean,
    val hideSystemNavigation: Boolean,
    initialAutofillEnabled: Boolean,
    initialAutofillServiceActive: Boolean,
    val autofillSupported: Boolean
) {
    var blockThirdPartyCookies by mutableStateOf(initialBlockThirdPartyCookies)
    var customUserAgent by mutableStateOf(initialCustomUserAgent)
    var httpsOnly by mutableStateOf(initialHttpsOnly)
    var doNotTrack by mutableStateOf(initialDoNotTrack)
    var stripTrackingParams by mutableStateOf(initialStripTrackingParams)
    var globalPrivacyControl by mutableStateOf(initialGlobalPrivacyControl)
    var webRtcProtection by mutableStateOf(initialWebRtcProtection)
    var clearOnExit by mutableStateOf(initialClearOnExit)
    var clearHistory by mutableStateOf(initialClearHistory)
    var clearCookies by mutableStateOf(initialClearCookies)
    var clearCache by mutableStateOf(initialClearCache)
    var clearTabs by mutableStateOf(initialClearTabs)
    var appLock by mutableStateOf(initialAppLock)
    var appLockTimeout by mutableStateOf(initialAppLockTimeout)
    var screenshotBlocking by mutableStateOf(initialScreenshotBlocking)
    var incognitoKeyboard by mutableStateOf(initialIncognitoKeyboard)
    var clipboardClearSeconds by mutableStateOf(initialClipboardClearSeconds)
    var clearOnExitDialogOpen by mutableStateOf(false)
    var appLockTimeoutDialogOpen by mutableStateOf(false)
    var clipboardDialogOpen by mutableStateOf(false)
    var autofillEnabled by mutableStateOf(initialAutofillEnabled)
    var autofillServiceActive by mutableStateOf(initialAutofillServiceActive)
}
