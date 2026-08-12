package io.github.pnck.gallery.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.pnck.gallery.R
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L

/**
 * Controls-only video page (owner's spec): play/pause, ±10s seek, drag seek,
 * rotate — every action is a control, no gestures (the pager owns horizontal
 * swipes; the two-finger rotate gesture was retired for the same reason).
 *
 * Rotation comes from the detail screen's top bar (same place as the image
 * rotate control) and is applied through the Media3 effect pipeline
 * ([ScaleAndRotateTransformation]) — the DECODER output is rotated, so pixels
 * and the aspect ratio stay correct; rotating the Compose canvas would only
 * spin the view and crush the frame. Effects can only be installed before
 * prepare(), so a rotation change rebuilds the player and resumes at the
 * saved position.
 *
 * Layout follows the classic gallery-player pattern (Google Photos & co.):
 * a centered transport row (back 10s / play-pause / forward 10s) above a
 * seek row of current-time — thin white track with a round thumb — duration,
 * padded clear of the system navigation bar.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class) // Slider's custom thumb slot
@Composable
fun VideoPlayerPage(
    /** content:// or file:// uri of the playable video. */
    uri: String,
    /** Clockwise rotation in degrees (0/90/180/270), driven by the top-bar control. */
    rotationDeg: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Resume state carried across player rebuilds (plain array, not compose
    // state — writing it in onDispose must not schedule a recomposition).
    val resume = remember { LongArray(2) } // [positionMs, isPlaying 0/1]
    val player = remember(rotationDeg) {
        ExoPlayer.Builder(context).build().apply {
            if (rotationDeg != 0f) {
                setVideoEffects(listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotationDeg).build()))
            }
            setMediaItem(MediaItem.fromUri(uri), resume[0])
            prepare()
            playWhenReady = resume[1] == 1L
        }
    }

    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        onDispose {
            resume[0] = player.currentPosition.coerceAtLeast(0)
            resume[1] = if (player.isPlaying) 1L else 0L
            player.removeListener(listener)
            player.release()
        }
    }

    // Position ticker (paused while the user drags the seek slider).
    LaunchedEffect(player, playing, dragging) {
        positionMs = player.currentPosition.coerceAtLeast(0)
        while (playing && !dragging) {
            delay(500)
            positionMs = player.currentPosition.coerceAtLeast(0)
        }
    }

    fun seekBy(deltaMs: Long) {
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L)))
        positionMs = player.currentPosition.coerceAtLeast(0)
    }

    Column(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // controls live below, per spec
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
        ) {
            // Transport: back 10s / play-pause / forward 10s.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { seekBy(-SEEK_STEP_MS) }) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.detail_seek_back),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.detail_pause else R.string.detail_play),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { seekBy(SEEK_STEP_MS) }) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.detail_seek_forward),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            // Seek: current time — thin white track, round thumb — duration.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatMs(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        dragging = true
                        positionMs = (frac * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(positionMs)
                        dragging = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    thumb = {
                        Box(Modifier.size(12.dp).background(Color.White, CircleShape))
                    },
                )
                Text(
                    formatMs(durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
