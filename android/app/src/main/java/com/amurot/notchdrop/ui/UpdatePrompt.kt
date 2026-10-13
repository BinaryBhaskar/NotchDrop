package com.amurot.notchdrop.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurot.notchdrop.service.Updates

/** Asked once: whether to look for new versions on GitHub automatically. */
@Composable
fun UpdateOptInDialog(onAnswer: (Boolean) -> Unit) {
    NdDialog(
        onDismiss = { onAnswer(false) },
        title = "Check for updates?",
        icon = NdIcons.Refresh,
        description = "NotchDrop can look for a new version on GitHub once a day and let you know. You can change this in Settings.",
    ) {
        NdButton("Not now", { onAnswer(false) }, style = NdButtonStyle.Text)
        NdButton("Check daily", { onAnswer(true) })
    }
}

@Composable
fun UpdateDialog(release: Updates.Release, onDownload: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val notes = release.notes.trim()
    val body: (@Composable ColumnScope.() -> Unit)? = if (notes.isEmpty()) null else {
        {
            Text(
                notes.take(1200), color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
    NdDialog(
        onDismiss = onLater,
        title = "Version ${release.version} is available",
        icon = NdIcons.Down,
        description = "You have ${Updates.current}.",
        content = body,
    ) {
        NdButton("Skip", onSkip, style = NdButtonStyle.Text)
        NdButton("Later", onLater, style = NdButtonStyle.Text)
        NdButton("Download", onDownload)
    }
}
