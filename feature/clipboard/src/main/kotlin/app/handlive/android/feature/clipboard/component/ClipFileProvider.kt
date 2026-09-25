package app.handlive.android.feature.clipboard.component

import androidx.core.content.FileProvider
import app.handlive.android.feature.clipboard.R

/**
 * `content://${applicationId}.clipfiles/clip/…` (CLIP-03 API 6): exposes only `cache/clip/`; the app that pastes
 * gets a temporary read grant from the clipboard. A subclass so it cannot clash with another library's provider.
 */
class ClipFileProvider : FileProvider(R.xml.clip_paths)
