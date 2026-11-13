package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraPrimaryDark
import com.example.ui.theme.NaraSurfaceVariant
import com.example.ui.theme.NaraTextPrimary
import com.example.ui.theme.NaraTextSecondary

@Composable
fun HeroBanner(
    banners: List<Drama>,
    onWatchClick: (Drama) -> Unit,
    onDetailClick: (Drama) -> Unit,
    modifier: Modifier = Modifier
) {
    if (banners.isEmpty()) return

    val pagerState = rememberPagerState(
        pageCount = { banners.size }
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
            .testTag("hero_banner_section")
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val drama = banners[page]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        onDetailClick(drama)
                    }
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    NaraBackground.copy(alpha = 0.5f),
                                    Color.Transparent,
                                    NaraBackground.copy(alpha = 0.8f),
                                    NaraBackground
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = 16.dp,
                            top = 65.dp
                        )
                        .clip(RoundedCornerShape(4.dp))
                        .background(NaraPrimaryDark)
                        .padding(
                            horizontal = 8.dp,
                            vertical = 3.dp
                        )
                ) {
                    Text(
                        text = "UNGGULAN HARI INI",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        )
                ) {
                    if (drama.categories.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            drama.categories.take(3).forEach { cat ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            NaraSurfaceVariant.copy(
                                                alpha = 0.8f
                                            )
                                        )
                                        .padding(
                                            horizontal = 6.dp,
                                            vertical = 2.dp
                                        )
                                ) {
                                    Text(
                                        text = cat,
                                        color = NaraTextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    ProviderLabel(
                        provider = drama.provider
                    )

                    Spacer(modifier = Modifier.height(7.dp))

                    Text(
                        text = drama.title,
                        color = NaraTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onWatchClick(drama)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NaraPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag(
                                "hero_watch_button_${drama.uniqueKey}"
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = "Tonton Sekarang",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onDetailClick(drama)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NaraTextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = NaraTextSecondary
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = "Detail",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = 16.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(banners.size) { index ->
                    val isSelected =
                        pagerState.currentPage == index

                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(
                                if (isSelected) 16.dp else 4.dp
                            )
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    NaraPrimary
                                } else {
                                    Color.White.copy(alpha = 0.35f)
                                }
                            )
                    )
                }
            }
        }
    }
}
