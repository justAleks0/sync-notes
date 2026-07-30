package com.justaleks.syncnotes.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justaleks.syncnotes.data.UpdateInfo

/**
 * Shown only when a newer release exists. When this build is current, the whole
 * thing renders nothing — being up to date should be silent.
 */
@Composable
fun UpdateBanner(
    update: UpdateInfo?,
    progress: Int?,
    onDownload: () -> Unit,
) {
    if (update == null) return

    val downloading = progress != null

    // A slow pulse to draw the eye, paused while the download runs.
    val transition = rememberInfiniteTransition(label = "update-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (downloading) 1f else 1.04f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "scale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF1B2440), Color(0xFF241D3F)))
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hey, there's a new update.", fontWeight = FontWeight.Bold)
                Text(
                    "Version ${update.version} is ready — you're on an older build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onDownload,
                enabled = !downloading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.scale(scale),
            ) {
                Text(
                    if (downloading) "Downloading… $progress%" else "Download now",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
