package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.ApiKeyStore
import com.example.data.db.FavoriteDramaEntity
import com.example.data.db.NaraDatabase
import com.example.data.db.WatchHistoryEntity
import com.example.data.repository.ApiException
import com.example.data.repository.NaraRepository
import com.example.model.Drama
import com.example.model.DramaDetail
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

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroBanners: List<Drama> = emptyList(),
    val rekomendasi: List<Drama> = emptyList(),
    val terpopuler: List<Drama> = emptyList(),
    val terbaru: List<Drama> = emptyList(),
    val romantis: List<Drama> = emptyList(),
    val keluarga: List<Drama> = emptyList(),
    val pilihan: List<Drama> = emptyList(),
    val errorMessage: String? = null,
    val errorCode: Int? = null
)

data class ExploreUiState(
    val query: String = "",
    val selectedCategory: String = "Semua",
    val isLoading: Boolean = false,
    val searchResults: List<Drama> = emptyList(),
    val errorMessage: String? = null
)

data class DetailUiState(
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

    private val _selectedDramaId = MutableStateFlow<String?>(null)
    val selectedDramaId: StateFlow<String?> = _selectedDramaId.asStateFlow()

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

    private val _hasCustomApiKey = MutableStateFlow(false)
    val hasCustomApiKey: StateFlow<Boolean> = _hasCustomApiKey.asStateFlow()

    private val _maskedCustomApiKey = MutableStateFlow("")
    val maskedCustomApiKey: StateFlow<String> = _maskedCustomApiKey.asStateFlow()

    init {
        ApiKeyStore.init(application)
        refreshApiKeyState()
        loadHomeData()
    }

    private fun refreshApiKeyState() {
        _hasCustomApiKey.value = ApiKeyStore.hasCustomApiKey()
        _maskedCustomApiKey.value = ApiKeyStore.getMaskedCustomApiKey()
    }

    fun saveCustomApiKey(apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isNotEmpty()) {
            ApiKeyStore.saveCustomApiKey(clean)
            refreshApiKeyState()
            loadHomeData()
        }
    }

    fun resetCustomApiKey() {
        ApiKeyStore.clearCustomApiKey()
        refreshApiKeyState()
        loadHomeData()
    }

    fun selectTab(tab: NaraTab) {
        _currentTab.value = tab
    }

    // --- HOME ACTIONS ---
    fun loadHomeData() {
        viewModelScope.launch {
            _homeState.value = _homeState.value.copy(isLoading = true, errorMessage = null, errorCode = null)
            val result = repository.getHomeDramas(offset = 0)
            if (result.isSuccess) {
                val list = result.getOrNull() ?: emptyList()
                categorizeHomeDramas(list)
            } else {
                val error = result.exceptionOrNull()
                val code = (error as? ApiException)?.code
                _homeState.value = _homeState.value.copy(
                    isLoading = false,
                    errorMessage = error?.message ?: "Gagal terhubung ke layanan Melolo.",
                    errorCode = code
                )
            }
        }
    }

    private fun categorizeHomeDramas(list: List<Drama>) {
        if (list.isEmpty()) {
            _homeState.value = _homeState.value.copy(
                isLoading = false,
                heroBanners = emptyList(),
                rekomendasi = emptyList(),
                terpopuler = emptyList(),
                terbaru = emptyList(),
                romantis = emptyList(),
                keluarga = emptyList(),
                pilihan = emptyList()
            )
            return
        }

        // Divide items organically across the 6 requested categories
        val banners = list.take(5)
        val rekomendasi = list.shuffled().take(10)
        val terpopuler = list.sortedByDescending { it.isPopular }.take(10)
        val terbaru = list.sortedByDescending { it.isLatest }.take(10)
        val romantis = list.filter { drama ->
            drama.categories.any { it.contains("Romantis", true) || it.contains("Cinta", true) || it.contains("Love", true) }
        }.ifEmpty { list.take(8) }
        val keluarga = list.filter { drama ->
            drama.categories.any { it.contains("Keluarga", true) || it.contains("Etika", true) || it.contains("Family", true) }
        }.ifEmpty { list.drop(2).take(8) }
        val pilihan = list.reversed().take(10)

        _homeState.value = _homeState.value.copy(
            isLoading = false,
            heroBanners = banners,
            rekomendasi = rekomendasi,
            terpopuler = terpopuler,
            terbaru = terbaru,
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
        performSearch(_exploreState.value.query.ifEmpty { category.takeIf { it != "Semua" } ?: "" })
    }

    fun performSearch(queryToSearch: String? = null) {
        val q = queryToSearch ?: _exploreState.value.query
        if (q.isBlank()) {
            _exploreState.value = _exploreState.value.copy(searchResults = emptyList(), isLoading = false)
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
                    errorMessage = result.exceptionOrNull()?.message ?: "Gagal mencari drama."
                )
            }
        }
    }

    // --- DETAIL ACTIONS ---
    fun openDramaDetail(dramaId: String) {
        _selectedDramaId.value = dramaId
        loadDramaDetail(dramaId)
    }

    fun closeDramaDetail() {
        _selectedDramaId.value = null
    }

    fun loadDramaDetail(dramaId: String) {
        viewModelScope.launch {
            _detailState.value = DetailUiState(dramaId = dramaId, isLoading = true)

            // Check favorite & history in parallel
            launch {
                repository.isFavorite(dramaId).collect { isFav ->
                    _detailState.value = _detailState.value.copy(isFavorite = isFav)
                }
            }
            launch {
                repository.getHistory(dramaId).collect { history ->
                    _detailState.value = _detailState.value.copy(lastHistory = history)
                }
            }

            val result = repository.getDramaDetail(dramaId)
            if (result.isSuccess) {
                _detailState.value = _detailState.value.copy(
                    isLoading = false,
                    detail = result.getOrNull(),
                    errorMessage = null
                )
            } else {
                _detailState.value = _detailState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Gagal memuat detail drama."
                )
            }
        }
    }

    fun toggleFavorite(drama: Drama) {
        viewModelScope.launch {
            val currentFav = _detailState.value.isFavorite
            repository.toggleFavorite(drama, currentFav)
        }
    }

    fun removeFavoriteById(dramaId: String) {
        viewModelScope.launch {
            repository.removeFavorite(dramaId)
        }
    }

    // --- PLAYER ACTIONS ---
    fun playDrama(drama: Drama, episodeNum: Int = 1) {
        _playerState.value = PlayerUiState(
            drama = drama,
            episodeNum = episodeNum,
            totalEpisodes = if (drama.totalEpisodes > 0) drama.totalEpisodes else 45,
            isLoading = true
        )
        loadVideoSource(drama.id, episodeNum)
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
        loadVideoSource(drama.id, targetEp)
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
        loadVideoSource(drama.id, current.episodeNum)
    }

    private fun loadVideoSource(dramaId: String, episodeNum: Int) {
        viewModelScope.launch {
            val result = repository.getVideoSource(dramaId, episodeNum)
            val current = _playerState.value ?: return@launch
            if (result.isSuccess) {
                _playerState.value = current.copy(
                    isLoading = false,
                    videoSource = result.getOrNull(),
                    errorMessage = null
                )
            } else {
                _playerState.value = current.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Gagal memuat video episode $episodeNum."
                )
            }
        }
    }

    fun saveWatchProgress(positionMs: Long, durationMs: Long) {
        val player = _playerState.value ?: return
        val drama = player.drama ?: return
        viewModelScope.launch {
            repository.saveWatchProgress(
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
        _playerState.value = null
    }

    fun deleteHistory(dramaId: String) {
        viewModelScope.launch {
            repository.deleteHistory(dramaId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}
