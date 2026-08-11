package io.github.pnck.gallery.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * Controls-only video page (owner's spec): play/pause, seek, rotate — every
 * action is a control, no gestures (the pager owns horizontal swipes; the
 * two-finger rotate gesture was retired for the same reason).
 */
@Composable
fun VideoPlayerPage(
    /** content:// or file:// uri of the playable video. */
    uri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var rotation by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    player.addListener(object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
        }
    })

    // Position ticker (paused while the user drags the seek slider).
    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            delay(500)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // controls live below, per spec
                    this.player = player
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .rotate(rotation),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { frac ->
                    dragging = true
                    positionMs = (frac * durationMs).toLong()
                },
                onValueChangeFinished = {
                    player.seekTo(positionMs)
                    dragging = false
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                color = Color.White,
                modifier = Modifier.padding(end = 4.dp),
            )
            IconButton(onClick = { rotation = (rotation + 90f) % 360f }) {
                Icon(Icons.Default.RotateRight, contentDescription = null, tint = Color.White)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
