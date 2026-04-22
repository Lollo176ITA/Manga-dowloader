package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MangaUiState(
    val query: String = "",
    val results: List<MangaSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val selected: MangaDetails? = null,
    val isLoadingDetails: Boolean = false,
    val errorMessage: String? = null,
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val client = MangapillClient(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        MangaUiState(query = prefs.getString(KEY_LAST_QUERY, "").orEmpty()),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun submitSearch() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            _state.value = _state.value.copy(results = emptyList(), errorMessage = null)
            return
        }
        prefs.edit().putString(KEY_LAST_QUERY, q).apply()
        searchJob?.cancel()
        _state.value = _state.value.copy(isSearching = true, errorMessage = null)
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { client.searchManga(q) }
                _state.value = _state.value.copy(results = results, isSearching = false)
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isSearching = false,
                    errorMessage = exc.message ?: "Errore di ricerca",
                )
            }
        }
    }

    fun selectManga(result: MangaSearchResult) {
        detailJob?.cancel()
        _state.value = _state.value.copy(
            isLoadingDetails = true,
            errorMessage = null,
            selected = MangaDetails(
                title = result.title,
                coverUrl = result.coverUrl,
                mangaUrl = result.mangaUrl,
                chapters = emptyList(),
            ),
        )
        detailJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) { client.fetchMangaDetails(result.mangaUrl) }
                _state.value = _state.value.copy(selected = details, isLoadingDetails = false)
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingDetails = false,
                    errorMessage = exc.message ?: "Errore caricamento manga",
                )
            }
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        _state.value = _state.value.copy(selected = null, isLoadingDetails = false, errorMessage = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val KEY_LAST_QUERY = "last_query"
    }
}
