package com.example.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.ads.StartIoAdManager
import com.example.data.api.NetworkClient
import com.example.model.DetectedVideoFormat
import com.example.player.CencMp4DataSource
import com.example.player.VideoFormatDetector
import com.example.ui.components.ErrorStateView
import com.example.ui.theme.NaraGold
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraSurfaceVariant
import com.example.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onSwitchEpisode: (Int) -> Unit,
    onSaveProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drama = playerState.drama

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }
    var isProbing by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    // Start.io session token bound to active drama + episode; cancels pending ad transitions on exit or manual switch
    val sessionToken = remember(drama?.uniqueKey, playerState.episodeNum) {
        StartIoAdManager.controller.startNewSession()
    }

    val latestEpisodeNum by rememberUpdatedState(playerState.episodeNum)
    val latestTotalEpisodes by rememberUpdatedState(playerState.totalEpisodes)
    val latestSessionToken by rememberUpdatedState(sessionToken)
    val latestOnSwitchEpisode by rememberUpdatedState(onSwitchEpisode)
    val latestOnSaveProgress by rememberUpdatedState(onSaveProgress)

    // Initialize ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Attach listeners
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> isBuffering = true
                    Player.STATE_READY -> {
                        isBuffering = false
                        totalDurationMs = exoPlayer.duration.coerceAtLeast(0L)
                        playerError = null
                        // Preload Start.io interstitial while current episode is actively playing
                        if (latestEpisodeNum < latestTotalEpisodes) {
                            StartIoAdManager.controller.preloadIfNeeded(context)
                        }
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        val hasNext = latestEpisodeNum < latestTotalEpisodes
                        if (hasNext) {
                            StartIoAdManager.controller.onEpisodeEnded(
                                context = context,
                                sessionToken = latestSessionToken,
                                hasNextEpisode = true,
                                onProceedNextEpisode = {
                                    latestOnSwitchEpisode(latestEpisodeNum + 1)
                                }
                            )
                        }
                    }
                    Player.STATE_IDLE -> isBuffering = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playerError = "Video tidak dapat diputar. Silakan coba kembali."
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            StartIoAdManager.controller.cancelPendingTransition()
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            latestOnSaveProgress(pos, dur)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Probe actual video format and recreate MediaSource cleanly on episode change or retry
    LaunchedEffect(playerState.videoSource, playerState.episodeNum, playerState.retryTrigger) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        isPlaying = false
        currentPositionMs = 0L
        totalDurationMs = 0L
        sliderPosition = 0f
        playerError = null

        val source = playerState.videoSource
        if (source == null || source.videoUrl.isBlank()) {
            isProbing = false
            return@LaunchedEffect
        }

        isProbing = true
        try {
            val outcome = VideoFormatDetector.probe(
                okHttpClient = NetworkClient.mediaOkHttpClient,
                url = source.videoUrl,
                decryptionKey = source.decryptionKey
            )

            if (outcome.diagnostics.failureReason != null) {
                isProbing = false
                playerError = "Video tidak dapat diputar. Silakan coba kembali."
                return@LaunchedEffect
            }

            val mediaItem = MediaItem.fromUri(Uri.parse(source.videoUrl))
            val mediaSource: MediaSource = when (outcome.diagnostics.detectedFormat) {
                DetectedVideoFormat.HLS -> {
                    HlsMediaSource.Factory(
                        OkHttpDataSource.Factory(NetworkClient.mediaOkHttpClient)
                    ).createMediaSource(mediaItem)
                }
                DetectedVideoFormat.DASH -> {
                    DashMediaSource.Factory(
                        OkHttpDataSource.Factory(NetworkClient.mediaOkHttpClient)
                    ).createMediaSource(mediaItem)
                }
                DetectedVideoFormat.MP4_CENC -> {
                    ProgressiveMediaSource.Factory(
                        CencMp4DataSource.Factory(
                            okHttpClient = NetworkClient.mediaOkHttpClient,
                            keyHex = source.decryptionKey
                        ),
                        DefaultExtractorsFactory()
                    ).createMediaSource(mediaItem)
                }
                DetectedVideoFormat.MP4_PLAIN, DetectedVideoFormat.MPEG_TS -> {
                    ProgressiveMediaSource.Factory(
                        OkHttpDataSource.Factory(NetworkClient.mediaOkHttpClient),
                        DefaultExtractorsFactory()
                    ).createMediaSource(mediaItem)
                }
                DetectedVideoFormat.INVALID_RESPONSE, DetectedVideoFormat.UNKNOWN -> {
                    isProbing = false
                    playerError = "Video tidak dapat diputar. Silakan coba kembali."
                    return@LaunchedEffect
                }
            }

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.play()
        } catch (_: Exception) {
            playerError = "Video tidak dapat diputar. Silakan coba kembali."
        } finally {
            isProbing = false
        }
    }

    // Periodic time & progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isDraggingSlider) {
                currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(1L)
                totalDurationMs = dur
                sliderPosition = (currentPositionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                onSaveProgress(currentPositionMs, totalDurationMs)
            }
            delay(1000)
        }
    }

    // Auto-hide controls after 4 seconds
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    BackHandler {
        StartIoAdManager.controller.cancelPendingTransition()
        onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player_screen")
    ) {
        // Video View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.resizeMode = if (isFullscreen) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showControls = !showControls }
                )
        )

        // Loading Spinner
        if (playerState.isLoading || isProbing || isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = NaraPrimary,
                    strokeWidth = 3.dp
                )
            }
        }

        // Clean Error Overlay (no developer/technical details)
        val errorToDisplay = playerError ?: playerState.errorMessage
        if (errorToDisplay != null && !playerState.isLoading && !isProbing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    ErrorStateView(
                        message = errorToDisplay,
                        onRetry = {
                            StartIoAdManager.controller.cancelPendingTransition()
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            onRetry()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (playerState.episodeNum > 1) {
                            Button(
                                onClick = {
                                    StartIoAdManager.controller.cancelPendingTransition()
                                    onSwitchEpisode(playerState.episodeNum - 1)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NaraSurfaceVariant)
                            ) {
                                Text("Episode Sebelumnya", fontSize = 12.sp)
                            }
                        }
                        if (playerState.episodeNum < playerState.totalEpisodes) {
                            Button(
                                onClick = {
                                    StartIoAdManager.controller.cancelPendingTransition()
                                    onSwitchEpisode(playerState.episodeNum + 1)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NaraSurfaceVariant)
                            ) {
                                Text("Episode Berikutnya", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Custom Overlay Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                )

                // Top Controls: Back, Title, Episode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            StartIoAdManager.controller.cancelPendingTransition()
                            onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
                            onBack()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = drama?.title ?: "Pemutar NARA",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Episode ${playerState.episodeNum} dari ${playerState.totalEpisodes}",
                            color = NaraGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Quality Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NaraPrimary.copy(alpha = 0.8f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = playerState.videoSource?.quality ?: "HD",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Center Playback Controls: Rewind, Play/Pause, Forward
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Mundur 10 detik",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Play / Pause Toggle
                    IconButton(
                        onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(NaraPrimary)
                            .testTag("player_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Jeda" else "Putar",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Forward 10s
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Maju 10 detik",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Bottom gradient bar & controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Episode Quick Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Episode Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (playerState.episodeNum > 1) NaraSurfaceVariant else Color.Transparent)
                                .clickable(enabled = playerState.episodeNum > 1) {
                                    StartIoAdManager.controller.cancelPendingTransition()
                                    onSwitchEpisode(playerState.episodeNum - 1)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = null,
                                tint = if (playerState.episodeNum > 1) Color.White else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Ep Sebelumnya",
                                color = if (playerState.episodeNum > 1) Color.White else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Next Episode Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (playerState.episodeNum < playerState.totalEpisodes) NaraSurfaceVariant else Color.Transparent)
                                .clickable(enabled = playerState.episodeNum < playerState.totalEpisodes) {
                                    StartIoAdManager.controller.cancelPendingTransition()
                                    onSwitchEpisode(playerState.episodeNum + 1)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ep Berikutnya",
                                color = if (playerState.episodeNum < playerState.totalEpisodes) Color.White else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                tint = if (playerState.episodeNum < playerState.totalEpisodes) Color.White else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Progress Slider
                    Slider(
                        value = sliderPosition,
                        onValueChange = { pos ->
                            isDraggingSlider = true
                            sliderPosition = pos
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val seekToMs = (sliderPosition * totalDurationMs).toLong()
                            exoPlayer.seekTo(seekToMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = NaraPrimary,
                            activeTrackColor = NaraPrimary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("player_progress_slider")
                    )

                    // Time and Fullscreen Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(currentPositionMs)} / ${formatTime(totalDurationMs)}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Layar Penuh",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
