package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Drama
import com.example.model.Episode
import com.example.ui.components.ErrorStateView
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraBorder
import com.example.ui.theme.NaraGold
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraPrimaryDark
import com.example.ui.theme.NaraSurface
import com.example.ui.theme.NaraSurfaceVariant
import com.example.ui.theme.NaraTextPrimary
import com.example.ui.theme.NaraTextSecondary
import com.example.ui.viewmodel.DetailUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    detailState: DetailUiState,
    onBack: () -> Unit,
    onPlayEpisode: (Drama, Int) -> Unit,
    onToggleFavorite: (Drama) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NaraBackground)
            .testTag("detail_screen")
    ) {
        when {
            detailState.isLoading && detailState.detail == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = NaraPrimary, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Memuat detail drama...", color = NaraTextSecondary, fontSize = 13.sp)
                    }
                }
            }

            detailState.errorMessage != null && detailState.detail == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorStateView(
                        message = detailState.errorMessage,
                        onRetry = onRetry
                    )
                }
            }

            detailState.detail != null -> {
                val dramaDetail = detailState.detail
                val drama = dramaDetail.drama
                val scrollState = rememberScrollState()
                var isSynopsisExpanded by remember { mutableStateOf(false) }

                val startEpisode = detailState.lastHistory?.lastEpisode ?: 1

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Hero Backdrop & Poster Area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                    ) {
                        // Blurred / Cover backdrop
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(drama.cover)
                                .crossfade(true)
                                .build(),
                            contentDescription = drama.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Multi-stop gradient
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            NaraBackground.copy(alpha = 0.5f),
                                            NaraBackground.copy(alpha = 0.85f),
                                            NaraBackground
                                        )
                                    )
                                )
                        )

                        // Back Button
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(12.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .testTag("detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kembali",
                                tint = Color.White
                            )
                        }

                        // Poster thumbnail and basic info overlay
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NaraSurfaceVariant)
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(drama.cover)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = drama.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = drama.title,
                                    color = NaraTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = NaraGold,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = drama.rating,
                                        color = NaraTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "${dramaDetail.episodes.size} Episode",
                                        color = NaraPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (dramaDetail.channel.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Kanal: ${dramaDetail.channel}",
                                        color = NaraTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Content Body
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Genre chips
                        if (drama.categories.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                drama.categories.forEach { cat ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(NaraSurfaceVariant)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = cat,
                                            color = NaraTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Main Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { onPlayEpisode(drama, startEpisode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NaraPrimary,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("detail_play_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (detailState.lastHistory != null) "Lanjutkan Ep $startEpisode" else "Tonton Sekarang",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { onToggleFavorite(drama) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (detailState.isFavorite) NaraPrimary else NaraTextPrimary
                                ),
                                modifier = Modifier
                                    .height(46.dp)
                                    .testTag("detail_favorite_button")
                            ) {
                                Icon(
                                    imageVector = if (detailState.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    tint = if (detailState.isFavorite) NaraPrimary else NaraTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (detailState.isFavorite) "Tersimpan" else "Favorit",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Synopsis Section
                        if (drama.synopsis.isNotEmpty()) {
                            Text(
                                text = "Sinopsis",
                                color = NaraTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = drama.synopsis,
                                color = NaraTextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (drama.synopsis.length > 120) {
                                Text(
                                    text = if (isSynopsisExpanded) "Sembunyikan" else "Baca Selengkapnya",
                                    color = NaraPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { isSynopsisExpanded = !isSynopsisExpanded }
                                        .padding(vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        // Episodes Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Daftar Episode",
                                color = NaraTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${dramaDetail.episodes.size} Episode Tersedia",
                                color = NaraTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Grid of Episode buttons (5 columns)
                        val episodes = dramaDetail.episodes
                        val chunkedEpisodes = episodes.chunked(5)

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            chunkedEpisodes.forEach { rowEps ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    rowEps.forEach { ep ->
                                        val isCurrentWatched = detailState.lastHistory?.lastEpisode == ep.episodeNum

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        isCurrentWatched -> NaraPrimaryDark
                                                        else -> NaraSurfaceVariant
                                                    }
                                                )
                                                .clickable { onPlayEpisode(drama, ep.episodeNum) }
                                                .testTag("episode_button_${ep.episodeNum}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${ep.episodeNum}",
                                                color = if (isCurrentWatched) Color.White else NaraTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (isCurrentWatched) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                    // Filler for incomplete rows
                                    if (rowEps.size < 5) {
                                        repeat(5 - rowEps.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}
