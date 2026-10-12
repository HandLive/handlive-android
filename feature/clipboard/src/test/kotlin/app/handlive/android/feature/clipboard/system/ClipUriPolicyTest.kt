package app.handlive.android.feature.clipboard.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CLIP-01 API 2 logic 7: which clip URIs [ClipUriPolicy] allows the reader to open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipUriPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun anExternalContentProviderIsReadable() {
        registerProvider(GALLERY_AUTHORITY, "com.example.photos")
        assertTrue(ClipUriPolicy.isReadable(context, Uri.parse("content://$GALLERY_AUTHORITY/image/1")))
    }

    @Test
    fun aNonContentSchemeIsNotReadable() {
        val emptyScheme =
            Uri
                .Builder()
                .authority(GALLERY_AUTHORITY)
                .path("/clip.png")
                .build()
        assertNull(emptyScheme.scheme)
        assertFalse(ClipUriPolicy.isReadable(context, Uri.parse("file://$GALLERY_AUTHORITY/clip.png")))
        assertFalse(ClipUriPolicy.isReadable(context, Uri.parse("android.resource://$GALLERY_AUTHORITY/1")))
        assertFalse(ClipUriPolicy.isReadable(context, emptyScheme))
    }

    @Test
    fun theAppsOwnProviderIsNotReadable() {
        val authority = "app.handlive.clipboard.own"
        registerProvider(authority, context.packageName)
        assertFalse(ClipUriPolicy.isReadable(context, Uri.parse("content://$authority/clip.png")))
    }

    @Test
    fun anUnresolvedAuthorityIsNotReadable() {
        assertFalse(ClipUriPolicy.isReadable(context, Uri.parse("content://com.missing.photos/clip.png")))
    }

    private fun registerProvider(
        authority: String,
        packageName: String,
    ) {
        shadowOf(context.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                this.authority = authority
                name = "$packageName.FakeProvider"
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                enabled = true
                exported = true
            },
        )
    }

    private companion object {
        const val GALLERY_AUTHORITY = "com.example.photos"
    }
}
