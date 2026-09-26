package app.handlive.android.ui.main

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLGroupedListScope
import app.handlive.android.core.design.component.HLGroupedRow
import app.handlive.android.core.design.component.HLIcon
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R

/** Warnings the tabs show at the top: SET-01 E1 (notifications off) and E2 (the service did not start). */
data class StatusBanners(
    val notificationsOff: Boolean = false,
    val serviceFailed: Boolean = false,
    val onOpenNotificationSettings: () -> Unit = {},
    val onRetryService: () -> Unit = {},
) {
    val any: Boolean get() = notificationsOff || serviceFailed
}

/** The button labels of the banners, resolved in composition for the list builder. */
class BannerLabels(
    val retry: String,
    val openNotificationSettings: String,
)

@Composable
fun bannerLabels() =
    BannerLabels(
        retry = stringResource(R.string.common_retry),
        openNotificationSettings = stringResource(R.string.setup_open_notification_settings),
    )

/** One group per warning: the sentence with a warning symbol, then its button. */
fun HLGroupedListScope.bannerSections(
    banners: StatusBanners,
    labels: BannerLabels,
) {
    if (banners.serviceFailed) {
        section(key = "banner-service") {
            row { WarningRow(R.string.error_service_start_failed) }
            actionRow(labels.retry, banners.onRetryService)
        }
    }
    if (banners.notificationsOff) {
        section(key = "banner-notifications") {
            row { WarningRow(R.string.setup_notifications_denied) }
            actionRow(labels.openNotificationSettings, banners.onOpenNotificationSettings)
        }
    }
}

@Composable
private fun WarningRow(text: Int) {
    HLGroupedRow {
        HLIcon(symbol = HLSymbol.Warning, contentDescription = null, tint = HandLiveTheme.colors.textOrange)
        BasicText(
            text = stringResource(text),
            style = HandLiveTheme.typography.subheadline.copy(color = HandLiveTheme.colors.label),
            modifier = Modifier.weight(1f),
        )
    }
}
