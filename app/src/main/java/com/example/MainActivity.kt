package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.model.Drama
import com.example.ui.components.NaraBottomNavigation
import com.example.ui.components.NaraTopBar
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.FavoriteScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraTheme
import com.example.ui.viewmodel.NaraTab
import com.example.ui.viewmodel.NaraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NaraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NaraTheme {
                NaraApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun NaraApp(
    viewModel: NaraViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val selectedDramaId by viewModel.selectedDramaId.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    val homeState by viewModel.homeState.collectAsState()
    val exploreState by viewModel.exploreState.collectAsState()
    val detailState by viewModel.detailState.collectAsState()
    val favoritesList by viewModel.favoritesList.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val hasCustomApiKey by viewModel.hasCustomApiKey.collectAsState()
    val maskedCustomApiKey by viewModel.maskedCustomApiKey.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NaraBackground)
    ) {
        when {
            // Fullscreen Video Player Mode
            playerState != null -> {
                PlayerScreen(
                    playerState = playerState!!,
                    onBack = { viewModel.closePlayer() },
                    onSwitchEpisode = { epNum -> viewModel.switchEpisode(epNum) },
                    onSaveProgress = { pos, dur -> viewModel.saveWatchProgress(pos, dur) },
                    onRetry = { viewModel.retryVideo() }
                )
            }

            // Drama Detail Screen Mode
            selectedDramaId != null -> {
                DetailScreen(
                    detailState = detailState,
                    onBack = { viewModel.closeDramaDetail() },
                    onPlayEpisode = { drama, epNum -> viewModel.playDrama(drama, epNum) },
                    onToggleFavorite = { drama -> viewModel.toggleFavorite(drama) },
                    onRetry = {
                        selectedDramaId?.let { viewModel.loadDramaDetail(it) }
                    }
                )
            }

            // Main Scaffold with Bottom Navigation
            else -> {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = NaraBackground,
                    topBar = {
                        if (currentTab == NaraTab.HOME) {
                            NaraTopBar(
                                onSearchClick = { viewModel.selectTab(NaraTab.EXPLORE) }
                            )
                        }
                    },
                    bottomBar = {
                        NaraBottomNavigation(
                            selectedTab = currentTab,
                            onTabSelected = { tab -> viewModel.selectTab(tab) }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "tab_transition"
                        ) { tab ->
                            when (tab) {
                                NaraTab.HOME -> {
                                    HomeScreen(
                                        homeState = homeState,
                                        onDramaClick = { drama -> viewModel.openDramaDetail(drama.id) },
                                        onWatchClick = { drama -> viewModel.playDrama(drama, 1) },
                                        onRetry = { viewModel.loadHomeData() }
                                    )
                                }

                                NaraTab.EXPLORE -> {
                                    ExploreScreen(
                                        exploreState = exploreState,
                                        onQueryChanged = { query -> viewModel.onSearchQueryChanged(query) },
                                        onSearchSubmit = { query -> viewModel.performSearch(query) },
                                        onCategorySelected = { cat -> viewModel.onCategoryFilterSelected(cat) },
                                        onDramaClick = { drama -> viewModel.openDramaDetail(drama.id) }
                                    )
                                }

                                NaraTab.HISTORY -> {
                                    HistoryScreen(
                                        historyList = historyList,
                                        onResumeWatch = { drama, ep -> viewModel.playDrama(drama, ep) },
                                        onDramaClick = { id -> viewModel.openDramaDetail(id) },
                                        onDeleteItem = { id -> viewModel.deleteHistory(id) },
                                        onClearAll = { viewModel.clearAllHistory() }
                                    )
                                }

                                NaraTab.FAVORITES -> {
                                    FavoriteScreen(
                                        favoritesList = favoritesList,
                                        onDramaClick = { drama -> viewModel.openDramaDetail(drama.id) }
                                    )
                                }

                                NaraTab.PROFILE -> {
                                    ProfileScreen(
                                        totalFavorites = favoritesList.size,
                                        totalHistory = historyList.size,
                                        hasCustomApiKey = hasCustomApiKey,
                                        maskedCustomApiKey = maskedCustomApiKey,
                                        onSaveCustomApiKey = { key -> viewModel.saveCustomApiKey(key) },
                                        onResetCustomApiKey = { viewModel.resetCustomApiKey() },
                                        onClearHistory = { viewModel.clearAllHistory() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
