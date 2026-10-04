package com.lorenzo.mangadownloader.app

enum class TutorialPhase {
    Idle,
    Welcome,
    Preloading,
    AwaitingSearchBar,
    AwaitingResultTap,
    AwaitingFavorite,
    AwaitingDownload,
    AwaitingFavoritesTab,
    AwaitingLibraryTab,
    AwaitingSeriesTap,
    AwaitingChapterTap,
    InReader,
    AwaitingOverflow,
    Closing,
    FallbackShowcase,
    FallbackClosing,
}

data class TutorialSample(
    val sourceId: String,
    val mangaUrl: String,
    val title: String,
    val coverUrl: String?,
    val chapterUrl: String,
)

data class TutorialUiState(
    val phase: TutorialPhase = TutorialPhase.Idle,
    val sample: TutorialSample? = null,
)
