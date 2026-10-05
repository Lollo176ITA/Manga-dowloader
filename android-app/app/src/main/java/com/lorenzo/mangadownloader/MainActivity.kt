package com.lorenzo.mangadownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.app.MangaViewModelFactory
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.report.CrashReporter
import com.lorenzo.mangadownloader.data.report.FeedbackReporter
import com.lorenzo.mangadownloader.ui.App
import com.lorenzo.mangadownloader.ui.LocalPlatformUi
import com.lorenzo.mangadownloader.ui.PlatformUi
import com.lorenzo.mangadownloader.ui.info.ReportProblemScreen
import com.lorenzo.mangadownloader.ui.widget.ReadingWidget
import com.lorenzo.mangadownloader.ui.widget.ReadingWidgetSettingsContent

class MainActivity : FragmentActivity() {
    override val defaultViewModelProviderFactory get() = MangaViewModelFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAniListRedirect(intent)
        handleNotificationIntent(intent)

        setContent {
            val viewModel: MangaViewModel = viewModel()
            val host = remember { AndroidPlatformHost(this) }
            CompositionLocalProvider(LocalPlatformUi provides AndroidPlatformUi) {
                App(viewModel = viewModel, host = host)
            }
            CrashReportUploadEffect()
        }
    }

    override fun onStop() {
        super.onStop()
        // L'app va in background: è lì che il widget torna visibile, quindi è il momento di
        // ridisegnarlo con le letture appena fatte.
        ViewModelProvider(this)[MangaViewModel::class.java].onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTask: i nuovi intent (redirect OAuth, tap su notifica) arrivano qui.
        setIntent(intent)
        handleAniListRedirect(intent)
        handleNotificationIntent(intent)
    }

    /** Consuma il redirect OAuth di AniList (`mangapp://anilist-auth#access_token=…`). */
    private fun handleAniListRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != AniListAuth.REDIRECT_SCHEME || uri.host != AniListAuth.REDIRECT_HOST) {
            return
        }
        intent.data = null
        ViewModelProvider(this)[MangaViewModel::class.java]
            .onAniListAuthRedirect(uri.fragment ?: uri.encodedQuery)
    }

    /**
     * Consuma gli extras del tap su una notifica "nuovo capitolo": apre direttamente il
     * dettaglio del manga (notifica singola) o il feed Aggiornamenti (riepilogo), invece
     * della tab generica. Gli extras vengono rimossi così non riscattano alla ricomposizione.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val viewModel = ViewModelProvider(this)[MangaViewModel::class.java]
        if (intent.getBooleanExtra(ReadingWidget.EXTRA_RESUME_READING, false)) {
            intent.removeExtra(ReadingWidget.EXTRA_RESUME_READING)
            viewModel.resumeLatestReading()
            return
        }
        if (intent.getBooleanExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED, false)) {
            intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED)
            viewModel.openUpdatesFromNotification()
            return
        }
        val mangaUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL) ?: return
        intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL)
        viewModel.openMangaFromNotification(
            sourceId = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_SOURCE_ID).orEmpty(),
            title = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_TITLE).orEmpty(),
            mangaUrl = mangaUrl,
            coverUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_COVER),
        )
    }
}

/** Le parti di interfaccia che esistono solo su Android: widget e segnalazioni via email. */
private val AndroidPlatformUi = PlatformUi(
    readingWidgetSettings = { ReadingWidgetSettingsContent() },
    reportProblem = { padding, onResult -> ReportProblemScreen(padding = padding, onResult = onResult) },
    isFeedbackConfigured = FeedbackReporter.isConfigured(),
    supportsDynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
)

/**
 * Crash del run precedente: la segnalazione parte da sola in background, senza disturbare
 * l'utente con stack trace incomprensibili (scelta deliberata). Se l'invio fallisce il report
 * resta su disco e si ritenta al prossimo avvio.
 */
@Composable
private fun CrashReportUploadEffect() {
    val appContext = LocalContext.current.applicationContext
    var lastCrashReport by remember { mutableStateOf(CrashReporter.readLastCrash(appContext)) }
    LaunchedEffect(lastCrashReport) {
        val report = lastCrashReport ?: return@LaunchedEffect
        if (!FeedbackReporter.isConfigured()) {
            CrashReporter.clearLastCrash(appContext)
            lastCrashReport = null
            return@LaunchedEffect
        }
        val sent = FeedbackReporter.sendCrashReport(appContext, report)
        if (sent) {
            CrashReporter.clearLastCrash(appContext)
        }
        lastCrashReport = null
    }
}
