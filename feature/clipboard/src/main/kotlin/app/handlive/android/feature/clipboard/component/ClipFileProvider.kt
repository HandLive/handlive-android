package app.handlive.android.feature.clipboard.component

import androidx.core.content.FileProvider

/**
 * `content://<applicationId>.clipfiles/clip/…` (CLIP-03 API 6): exposes only `cache/clip/` (`res/xml/clip_paths.xml`);
 * the app that pastes gets a temporary read grant from the clipboard. A subclass so it cannot clash with another
 * library's provider in the merged manifest.
 */
class ClipFileProvider : FileProvider()
