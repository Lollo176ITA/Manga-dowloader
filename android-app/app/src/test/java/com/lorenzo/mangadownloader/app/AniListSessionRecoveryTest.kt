package com.lorenzo.mangadownloader.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.anilist.AniListScoreFormat
import com.lorenzo.mangadownloader.data.anilist.AniListStore
import com.lorenzo.mangadownloader.data.anilist.AniListViewer
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.data.update.AppUpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ripresa della sessione AniList senza riavviare l'app.
 *
 * Il sintomo che questi test bloccano: dopo la scadenza della sessione, un nuovo login non
 * "prendeva" e l'unico rimedio era chiudere e riaprire l'app. Le due cause erano lo stato in
 * memoria che poteva restare disallineato da quello su disco, e il redirect OAuth
 * ri-elaborabile con un token ormai vecchio.
 *
 * In Robolectric il main looper è in pausa: i job lanciati da [MangaViewModel] restano in coda
 * e non partono, quindi qui si osserva solo la parte sincrona — che è esattamente quella che
 * decide se il login viene tentato o scartato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AniListSessionRecoveryTest {

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var accountPrefs: SharedPreferences

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        accountPrefs = application.getSharedPreferences(AniListStore.ACCOUNT_PREFS_NAME, Context.MODE_PRIVATE)
        accountPrefs.edit().clear().commit()
    }

    @Test
    fun `syncAniListAccountState recupera l account persistito rimasto fuori dallo stato`() {
        val viewModel = createViewModel()
        assertNull("precondizione: nessun account in memoria", viewModel.state.value.aniList.viewer)

        // Login andato a buon fine su disco ma non riflesso in memoria: è la situazione che
        // prima si sbloccava solo riavviando l'app.
        connectedStore().persistAccount(TOKEN, VIEWER)
        viewModel.syncAniListAccountState()

        assertEquals(VIEWER, viewModel.state.value.aniList.viewer)
    }

    @Test
    fun `syncAniListAccountState azzera l account quando il token non c e piu`() {
        connectedStore().persistAccount(TOKEN, VIEWER)
        val viewModel = createViewModel()
        assertNotNull("precondizione: account caricato all'avvio", viewModel.state.value.aniList.viewer)

        connectedStore().clearAccount()
        viewModel.syncAniListAccountState()

        assertNull(viewModel.state.value.aniList.viewer)
    }

    @Test
    fun `il redirect del login avviato dall app collega l account`() {
        val viewModel = createViewModel()
        val loginState = startLogin(viewModel)

        viewModel.onAniListAuthRedirect(redirect(TOKEN, loginState))

        // Il collegamento parte davvero (la chiamata di rete resta in coda).
        assertEquals(true, viewModel.state.value.aniList.isConnecting)
    }

    @Test
    fun `un redirect senza login in corso viene ignorato`() {
        val viewModel = createViewModel()

        // Link `mangapp://anilist-auth#access_token=…` aperto da fuori, con il token di un altro.
        viewModel.onAniListAuthRedirect("access_token=$TOKEN&token_type=Bearer")

        assertFalse(viewModel.state.value.aniList.isConnecting)
        assertNull(connectedStore().readToken())
        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `un redirect con state sbagliato non consuma il login in corso`() {
        val viewModel = createViewModel()
        val loginState = startLogin(viewModel)

        viewModel.onAniListAuthRedirect(redirect("token-altrui", "state-inventato"))
        assertFalse(viewModel.state.value.aniList.isConnecting)

        viewModel.onAniListAuthRedirect(redirect(TOKEN, loginState))
        assertEquals(true, viewModel.state.value.aniList.isConnecting)
    }

    @Test
    fun `lo state vale una volta sola`() {
        val viewModel = createViewModel()
        val loginState = startLogin(viewModel)
        viewModel.onAniListAuthRedirect(redirect(TOKEN, loginState))

        // Stesso redirect riconsegnato da Android dopo un process death: il token nel frattempo
        // può essere scaduto, e ritentarlo produrrebbe un 401 che scollega un account valido.
        val restarted = createViewModel()
        restarted.onAniListAuthRedirect(redirect(TOKEN, loginState))

        assertFalse(restarted.state.value.aniList.isConnecting)
    }

    @Test
    fun `il vecchio token del redirect salvato in chiaro viene cancellato`() {
        prefs.edit().putString("anilist_handled_auth_token", TOKEN).commit()

        connectedStore()

        assertFalse(prefs.contains("anilist_handled_auth_token"))
    }

    @Test
    fun `l account salvato nel file comune passa nel file escluso dal backup`() {
        prefs.edit()
            .putString("anilist_token", TOKEN)
            .putInt("anilist_viewer_id", VIEWER.id)
            .putString("anilist_viewer_name", VIEWER.name)
            .putString("anilist_viewer_score_format", VIEWER.scoreFormat.name)
            .commit()

        val store = connectedStore()

        assertEquals(TOKEN, store.readToken())
        assertEquals(VIEWER, store.readViewer())
        assertTrue(prefs.all.keys.none { it.startsWith("anilist_token") || it.startsWith("anilist_viewer") })
        assertEquals(TOKEN, accountPrefs.getString("anilist_token", null))
    }

    private fun startLogin(viewModel: MangaViewModel): String =
        AniListAuth.extractState(viewModel.beginAniListLogin().substringAfter('?'))!!

    private fun redirect(token: String, loginState: String): String =
        "access_token=$token&token_type=Bearer&state=$loginState"

    private fun connectedStore(): AniListStore = AniListStore(prefs, accountPrefs)

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    private companion object {
        const val TOKEN = "token-di-prova"
        val VIEWER = AniListViewer(id = 7, name = "lettore", scoreFormat = AniListScoreFormat.POINT_10)
    }
}
