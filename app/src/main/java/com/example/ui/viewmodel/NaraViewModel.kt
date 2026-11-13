package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ads.StartIoAdManager
import com.example.data.db.FavoriteDramaEntity
import com.example.data.db.NaraDatabase
import com.example.data.db.WatchHistoryEntity
import com.example.data.repository.MergedHomeCatalog
import com.example.data.repository.NaraRepository
import com.example.model.Drama
import com.example.model.DramaDetail
import com.example.model.DramaProvider
import com.example.model.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NaraTab(val label: String) {
    HOME("Beranda"),
    EXPLORE("Jelajahi"),
    HISTORY("Riwayat"),
    FAVORITES("Favorit"),
    PROFILE("Profil")
}

data class DramaTarget(
    val provider: DramaProvider,
    val dramaId: String
)

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroBanners: List<Drama> = emptyList(),
    val rekomendasi: List<Drama> = emptyList(),
    val terpopuler: List<Drama> = emptyList(),
    val terbaru: List<Drama> = emptyList(),
    val romantis: List<Drama> = emptyList(),
    val keluarga: List<Drama> = emptyList(),
    val pilihan: List<Drama> = emptyList(),
    val errorMessage: String? = null
)

data class ExploreUiState(
    val query: String = "",
    val selectedCategory: String = "Semua",
    val isLoading: Boolean = false,
    val searchResults: List<Drama> = emptyList(),
    val errorMessage: String? = null
)

data class DetailUiState(
    val provider: DramaProvider = DramaProvider.MELOLO,
    val dramaId: String = "",
    val isLoading: Boolean = false,
    val detail: DramaDetail? = null,
    val isFavorite: Boolean = false,
    val lastHistory: WatchHistoryEntity? = null,
    val errorMessage: String? = null
)

data class PlayerUiState(
    val drama: Drama? = null,
    val episodeNum: Int = 1,
    val totalEpisodes: Int = 1,
    val isLoading: Boolean = false,
    val videoSource: VideoSource? = null,
    val errorMessage: String? = null,
    val retryTrigger: Int = 0
)

class NaraViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NaraDatabase.getInstance(application)
    private val repository = NaraRepository(dao = database.naraDao())

    // --- NAVIGATION STATE ---
    private val _currentTab = MutableStateFlow(NaraTab.HOME)
    val currentTab: StateFlow<NaraTab> = _currentTab.asStateFlow()

    private val _selectedDrama = MutableStateFlow<DramaTarget?>(null)
    val selectedDrama: StateFlow<DramaTarget?> = _selectedDrama.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerUiState?>(null)
    val playerState: StateFlow<PlayerUiState?> = _playerState.asStateFlow()

    // --- TAB STATES ---
    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _exploreState = MutableStateFlow(ExploreUiState())
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _detailState = MutableStateFlow(DetailUiState())
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    // --- DB FLOWS ---
    val favoritesList: StateFlow<List<FavoriteDramaEntity>> = repository.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyList: StateFlow<List<WatchHistoryEntity>> = repository.getAllHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        StartIoAdManager.controller.initialize(application)
        loadHomeData()
    }

    fun selectTab(tab: NaraTab) {
        _currentTab.value = tab
    }

    // --- HOME ACTIONS ---
    fun loadHomeData() {
        viewModelScope.launch {
            _homeState.value = _homeState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.getHomeCatalog()
            if (result.isSuccess) {
                val catalog = result.getOrNull()
                if (catalog != null) {
                    applyMergedHomeCatalog(catalog)
                } else {
                    _homeState.value = _homeState.value.copy(isLoading = false)
                }
            } else {
                _homeState.value = _homeState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: "Koneksi sedang bermasalah. Silakan coba kembali."
                )
            }
        }
    }

    private fun applyMergedHomeCatalog(catalog: MergedHomeCatalog) {
        val all = catalog.allCombined
        if (all.isEmpty()) {
            _homeState.value = _homeState.value.copy(
                isLoading = false,
                heroBanners = emptyList(),
                rekomendasi = emptyList(),
                terpopuler = emptyList(),
                terbaru = emptyList(),
                romantis = emptyList(),
                keluarga = emptyList(),
                pilihan = emptyList(),
                errorMessage = null
            )
            return
        }

        val romantis = all.filter { drama ->
            drama.categories.any {
                it.contains("Romantis", true) || it.contains("Romansa", true) ||
                    it.contains("Cinta", true) || it.contains("Love", true)
            } || drama.title.contains("Cinta", true)
        }.take(15).ifEmpty { all.take(10) }

        val keluarga = all.filter { drama ->
            drama.categories.any {
                it.contains("Keluarga", true) || it.contains("Ibu", true) ||
                    it.contains("Pernikahan", true) || it.contains("Family", true) ||
                    it.contains("Etika", true)
            }
        }.take(15).ifEmpty { all.drop(2).take(10).ifEmpty { all } }

        val pilihan = all.reversed().take(15)

        _homeState.value = _homeState.value.copy(
            isLoading = false,
            heroBanners = catalog.banners,
            rekomendasi = catalog.rekomendasi.take(15),
            terpopuler = catalog.terpopuler.take(15),
            terbaru = catalog.terbaru.take(15),
            romantis = romantis,
            keluarga = keluarga,
            pilihan = pilihan,
            errorMessage = null
        )
    }

    // --- EXPLORE ACTIONS ---
    fun onSearchQueryChanged(newQuery: String) {
        _exploreState.value = _exploreState.value.copy(query = newQuery)
    }

    fun onCategoryFilterSelected(category: String) {
        _exploreState.value = _exploreState.value.copy(selectedCategory = category)
        val effectiveQuery = _exploreState.value.query.ifEmpty {
            category.takeIf { it != "Semua" } ?: ""
        }
        performSearch(effectiveQuery)
    }

    fun performSearch(queryToSearch: String? = null) {
        val q = queryToSearch ?: _exploreState.value.query
        if (q.isBlank()) {
            _exploreState.value = _exploreState.value.copy(
                searchResults = emptyList(),
                isLoading = false,
                errorMessage = null
            )
            return
        }

        viewModelScope.launch {
            _exploreState.value = _exploreState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.searchDramas(q)
            if (result.isSuccess) {
                _exploreState.value = _exploreState.value.copy(
                    isLoading = false,
                    searchResults = result.getOrNull() ?: emptyList(),
                    errorMessage = null
                )
            } else {
                _exploreState.value = _exploreState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: "Koneksi sedang bermasalah. Silakan coba kembali."
                )
            }
        }
    }

    // --- DETAIL ACTIONS ---
    fun openDramaDetail(drama: Drama) {
        openDramaDetail(drama.provider, drama.id)
    }

    fun openDramaDetail(provider: DramaProvider, dramaId: String) {
        val target = DramaTarget(provider = provider, dramaId = dramaId)
        _selectedDrama.value = target
        loadDramaDetail(provider, dramaId)
    }

    fun closeDramaDetail() {
        _selectedDrama.value = null
    }

    fun loadDramaDetail(provider: DramaProvider, dramaId: String) {
        viewModelScope.launch {
            _detailState.value = DetailUiState(
                provider = provider,
                dramaId = dramaId,
                isLoading = true
            )

            launch {
                repository.isFavorite(provider, dramaId).collect { isFav ->
                    val current = _detailState.value
                    if (current.provider == provider && current.dramaId == dramaId) {
                        _detailState.value = current.copy(isFavorite = isFav)
                    }
                }
            }
            launch {
                repository.getHistory(provider, dramaId).collect { history ->
                    val current = _detailState.value
                    if (current.provider == provider && current.dramaId == dramaId) {
                        _detailState.value = current.copy(lastHistory = history)
                    }
                }
            }

            val result = repository.getDramaDetail(provider, dramaId)
            val current = _detailState.value
            if (current.provider == provider && current.dramaId == dramaId) {
                if (result.isSuccess) {
                    _detailState.value = current.copy(
                        isLoading = false,
                        detail = result.getOrNull(),
                        errorMessage = null
                    )
                } else {
                    _detailState.value = current.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message
                            ?: "Drama tidak tersedia. Silakan coba kembali."
                    )
                }
            }
        }
    }

    fun toggleFavorite(drama: Drama) {
        viewModelScope.launch {
            val currentFav = _detailState.value.isFavorite
            repository.toggleFavorite(drama, currentFav)
        }
    }

    fun removeFavoriteById(provider: DramaProvider, dramaId: String) {
        viewModelScope.launch {
            repository.removeFavorite(provider, dramaId)
        }
    }

    // --- PLAYER ACTIONS ---
    fun playDrama(drama: Drama, episodeNum: Int = 1) {
        StartIoAdManager.controller.cancelPendingTransition()
        val resolvedTotal = if (drama.totalEpisodes > 0) drama.totalEpisodes else 1
        val targetEp = episodeNum.coerceIn(1, resolvedTotal)
        _playerState.value = PlayerUiState(
            drama = drama,
            episodeNum = targetEp,
            totalEpisodes = resolvedTotal,
            isLoading = true
        )
        loadVideoSource(drama.provider, drama.id, targetEp, forceRefresh = false)
    }

    fun switchEpisode(episodeNum: Int) {
        val current = _playerState.value ?: return
        val total = current.totalEpisodes
        val targetEp = episodeNum.coerceIn(1, if (total > 0) total else 999)
        val drama = current.drama ?: return

        _playerState.value = current.copy(
            episodeNum = targetEp,
            isLoading = true,
            videoSource = null,
            errorMessage = null,
            retryTrigger = current.retryTrigger + 1
        )
        loadVideoSource(drama.provider, drama.id, targetEp, forceRefresh = false)
    }

    fun retryVideo() {
        val current = _playerState.value ?: return
        val drama = current.drama ?: return
        _playerState.value = current.copy(
            isLoading = true,
            videoSource = null,
            errorMessage = null,
            retryTrigger = current.retryTrigger + 1
        )
        loadVideoSource(drama.provider, drama.id, current.episodeNum, forceRefresh = true)
    }

    private fun loadVideoSource(
        provider: DramaProvider,
        dramaId: String,
        episodeNum: Int,
        forceRefresh: Boolean
    ) {
        viewModelScope.launch {
            val result = repository.getVideoSource(provider, dramaId, episodeNum, forceRefresh)
            val current = _playerState.value ?: return@launch
            if (current.drama?.id != dramaId ||
                current.drama.provider != provider ||
                current.episodeNum != episodeNum
            ) {
                return@launch
            }
            if (result.isSuccess) {
                _playerState.value = current.copy(
                    isLoading = false,
                    videoSource = result.getOrNull(),
                    errorMessage = null
                )
            } else {
                _playerState.value = current.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: "Video tidak dapat diputar. Silakan coba kembali."
                )
            }
        }
    }

    fun saveWatchProgress(positionMs: Long, durationMs: Long) {
        val player = _playerState.value ?: return
        val drama = player.drama ?: return
        viewModelScope.launch {
            repository.saveWatchProgress(
                provider = drama.provider,
                dramaId = drama.id,
                title = drama.title,
                cover = drama.cover,
                episodeNum = player.episodeNum,
                positionMs = positionMs,
                durationMs = durationMs,
                totalEpisodes = player.totalEpisodes
            )
        }
    }

    fun closePlayer() {
        StartIoAdManager.controller.cancelPendingTransition()
        _playerState.value = null
    }

    fun deleteHistory(provider: DramaProvider, dramaId: String) {
        viewModelScope.launch {
            repository.deleteHistory(provider, dramaId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}
