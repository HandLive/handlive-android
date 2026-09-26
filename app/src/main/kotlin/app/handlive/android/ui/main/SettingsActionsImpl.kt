package app.handlive.android.ui.main

import android.content.Context
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.settings.AppLanguageSetting
import app.handlive.android.ui.settings.AutoSendStatus
import app.handlive.android.ui.settings.SettingsActions
import app.handlive.android.ui.settings.SettingsPage
import app.handlive.android.ui.settings.SettingsUiState
import app.handlive.android.ui.system.SystemPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SET-02 writes: each switch saves its key (0.9.5); keys that shape the capability reach the clients through
 * `capability/update` (the connection runtime watches the settings). Auto-send goes through the disclosure unless
 * the consent and the service already exist (CLIP-01 A1); turning it off makes the service turn itself off.
 */
class SettingsActionsImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val main: MainContext,
    private val state: SettingsUiState,
) : SettingsActions {
    private val store = main.dependencies.data.settings

    override fun setClipboard(enabled: Boolean) = save { store.set(SettingsKeys.FEATURE_CLIPBOARD, enabled) }

    override fun setAutoSend(enabled: Boolean) {
        when {
            !enabled -> {
                save {
                    main.dependencies.clipboard.consent
                        .sendManually()
                }
            }

            state.settings.clipA11yConsentAt == null -> {
                main.push(Route.Consent)
            }

            state.autoSendStatus == AutoSendStatus.NEEDS_ACCESSIBILITY && !state.accessibilityServiceOn -> {
                save { store.set(SettingsKeys.CLIP_AUTO_SEND, true) }
                main.push(Route.Consent)
            }

            else -> {
                save { store.set(SettingsKeys.CLIP_AUTO_SEND, true) }
            }
        }
    }

    override fun setSendImages(enabled: Boolean) = save { store.set(SettingsKeys.CLIP_SEND_IMAGES, enabled) }

    override fun setBlockSensitive(enabled: Boolean) = save { store.set(SettingsKeys.CLIP_BLOCK_SENSITIVE, enabled) }

    override fun setAutoClear(seconds: Int) = save { store.set(SettingsKeys.CLIP_AUTO_CLEAR_S, seconds) }

    override fun setInternet(enabled: Boolean) = save { store.set(SettingsKeys.RELAY_ENABLED, enabled) }

    override fun open(page: SettingsPage) {
        when (page) {
            SettingsPage.AUTO_CLEAR -> {
                main.push(Route.AutoClear)
            }

            SettingsPage.PERMISSIONS -> {
                main.push(Route.Permissions)
            }

            SettingsPage.LANGUAGE -> {
                if (AppLanguageSetting.usesSystemPage) {
                    SystemPages.open(context, AppLanguageSetting.systemPageIntent(context))
                } else {
                    main.push(Route.Language)
                }
            }
        }
    }

    private fun save(write: suspend () -> Unit) {
        scope.launch { write() }
    }
}
