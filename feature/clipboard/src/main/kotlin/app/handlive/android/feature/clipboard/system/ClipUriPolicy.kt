package app.handlive.android.feature.clipboard.system

import android.content.Context
import android.net.Uri

/**
 * CLIP-01 API 2 logic 7. A clip URI may be opened only when its scheme is `content` and
 * `PackageManager.resolveContentProvider` returns a provider in another package. Anything else is refused.
 * The path is never logged.
 */
internal object ClipUriPolicy {
    fun isReadable(
        context: Context,
        uri: Uri,
    ): Boolean {
        val authority = uri.authority
        if (uri.scheme != CONTENT || authority.isNullOrEmpty()) return false
        val owner = context.packageManager.resolveContentProvider(authority, 0)?.packageName
        return owner != null && owner != context.packageName
    }

    /** Authority of a `content` URI, or `-`. Never the path. */
    fun logAuthority(uri: Uri): String = uri.authority?.takeIf { uri.scheme == CONTENT } ?: "-"

    private const val CONTENT = "content"
}
