package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Drama
import com.example.ui.components.CategorySection
import com.example.ui.components.ErrorStateView
import com.example.ui.components.HeroBanner
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraTextSecondary
import com.example.ui.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    homeState: HomeUiState,
    onDramaClick: (Drama) -> Unit,
    onWatchClick: (Drama) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NaraBackground)
            .testTag("home_screen")
    ) {
        if (homeState.isLoading && homeState.heroBanners.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = NaraPrimary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Memuat drama terbaik untukmu...",
                        color = NaraTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (homeState.errorMessage != null && homeState.heroBanners.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ErrorStateView(
                    message = homeState.errorMessage,
                    errorCode = homeState.errorCode,
                    onRetry = onRetry
                )
            }
        } else {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // Large Featured Hero Banner
                if (homeState.heroBanners.isNotEmpty()) {
                    HeroBanner(
                        banners = homeState.heroBanners,
                        onWatchClick = onWatchClick,
                        onDetailClick = onDramaClick
                    )
                }

                // Inline banner error if partial
                if (homeState.errorMessage != null && homeState.heroBanners.isNotEmpty()) {
                    ErrorStateView(
                        message = homeState.errorMessage,
                        errorCode = homeState.errorCode,
                        onRetry = onRetry,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // 1. Rekomendasi Untukmu
                if (homeState.rekomendasi.isNotEmpty()) {
                    CategorySection(
                        title = "Rekomendasi Untukmu",
                        dramas = homeState.rekomendasi,
                        onDramaClick = onDramaClick
                    )
                }

                // 2. Drama Terpopuler
                if (homeState.terpopuler.isNotEmpty()) {
                    CategorySection(
                        title = "Drama Terpopuler",
                        dramas = homeState.terpopuler,
                        onDramaClick = onDramaClick
                    )
                }

                // 3. Drama Terbaru
                if (homeState.terbaru.isNotEmpty()) {
                    CategorySection(
                        title = "Drama Terbaru",
                        dramas = homeState.terbaru,
                        onDramaClick = onDramaClick
                    )
                }

                // 4. Drama Romantis
                if (homeState.romantis.isNotEmpty()) {
                    CategorySection(
                        title = "Drama Romantis",
                        dramas = homeState.romantis,
                        onDramaClick = onDramaClick
                    )
                }

                // 5. Drama Keluarga
                if (homeState.keluarga.isNotEmpty()) {
                    CategorySection(
                        title = "Drama Keluarga",
                        dramas = homeState.keluarga,
                        onDramaClick = onDramaClick
                    )
                }

                // 6. Drama Pilihan
                if (homeState.pilihan.isNotEmpty()) {
                    CategorySection(
                        title = "Drama Pilihan",
                        dramas = homeState.pilihan,
                        onDramaClick = onDramaClick
                    )
                }

                // Extra padding at bottom for navigation bar
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
