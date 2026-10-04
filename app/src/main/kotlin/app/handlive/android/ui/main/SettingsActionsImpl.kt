package app.handlive.android.ui.main

import android.content.Context
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.settings.AppLanguageSetting
import app.handlive.android.ui.settings.FeatureStatus
import app.handlive.android.ui.settings.PhoneFeature
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
        if (!enabled) {
            save {
                main.dependencies.clipboard.consent
                    .sendManually()
            }
            return
        }
        when (autoSendStep(state)) {
            AutoSendStep.DISCLOSURE -> {
                main.push(Route.Consent)
            }

            AutoSendStep.SERVICE -> {
                save { store.set(SettingsKeys.CLIP_AUTO_SEND, true) }
                openAutoSendAccess(context, main)
            }

            AutoSendStep.SAVE -> {
                save { store.set(SettingsKeys.CLIP_AUTO_SEND, true) }
            }
        }
    }

    override fun setSendImages(enabled: Boolean) = save { store.set(SettingsKeys.CLIP_SEND_IMAGES, enabled) }

    override fun setBlockSensitive(enabled: Boolean) = save { store.set(SettingsKeys.CLIP_BLOCK_SENSITIVE, enabled) }

    override fun setAutoClear(seconds: Int) = save { store.set(SettingsKeys.CLIP_AUTO_CLEAR_S, seconds) }

    override fun setInternet(enabled: Boolean) = save { store.set(SettingsKeys.RELAY_ENABLED, enabled) }

    override fun setFeature(
        feature: PhoneFeature,
        enabled: Boolean,
    ) {
        save { store.set(keyOf(feature), enabled) }
        // SET-02 step 3: the key is saved as true even when the permissions end up denied.
        if (enabled) primerOnSwitch(feature, state)?.let(main::push)
    }

    override fun grantFeature(feature: PhoneFeature) {
        if (state.status(feature) == FeatureStatus.PERMISSION_DENIED) {
            SystemPages.open(context, SystemPages.appDetails(context))
        } else {
            main.push(primerOf(feature))
        }
    }

    override fun open(page: SettingsPage) {
        when (page) {
            SettingsPage.AUTO_CLEAR -> {
                main.push(Route.AutoClear)
            }

            SettingsPage.PERMISSIONS -> {
                main.push(Route.Permissions())
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

/** What turning automatic sending on leads to (CLIP-01 A1). */
enum class AutoSendStep {
    /** No consent yet: the disclosure (CLIP-01 A2). */
    DISCLOSURE,

    /** The consent exists but the service is off: never the disclosure again, the way to the service instead. */
    SERVICE,

    /** Consent and service are there: the setting alone. */
    SAVE,
}

fun autoSendStep(state: SettingsUiState): AutoSendStep =
    when {
        state.settings.clipA11yConsentAt == null -> AutoSendStep.DISCLOSURE
        !state.accessibilityServiceOn -> AutoSendStep.SERVICE
        else -> AutoSendStep.SAVE
    }

/**
 * SET-02 step 3: the primer when [feature] is switched on with its access missing. The Notification access primer of
 * calls from other apps comes only while `feature.call` is on too (SET-01 N1): with Calls off they stay off anyway.
 */
fun primerOnSwitch(
    feature: PhoneFeature,
    state: SettingsUiState,
): Route? {
    val callsOn = feature != PhoneFeature.APP_CALLS || state.settings.callEnabled
    val missing = state.access(feature).status(enabled = true) == FeatureStatus.NEEDS_PERMISSION
    return primerOf(feature).takeIf { callsOn && missing }
}

/** SET-01 part B: the primer route of a feature; calls from other apps need the Notification access primer (N1). */
fun primerOf(feature: PhoneFeature): Route =
    when (feature) {
        PhoneFeature.SMS -> Route.SmsPermission
        PhoneFeature.CALLS -> Route.CallPermission
        PhoneFeature.APP_CALLS -> Route.NotificationAccess
    }

/** The settings key of a feature's switch (0.9.5). */
private fun keyOf(feature: PhoneFeature) =
    when (feature) {
        PhoneFeature.SMS -> SettingsKeys.FEATURE_SMS
        PhoneFeature.CALLS -> SettingsKeys.FEATURE_CALL
        PhoneFeature.APP_CALLS -> SettingsKeys.CALL_APP_CALLS
    }
