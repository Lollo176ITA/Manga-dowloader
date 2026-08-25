package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
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
    fun `un redirect gia elaborato non ritenta il login`() {
        val viewModel = createViewModel()

        viewModel.onAniListAuthRedirect("access_token=$TOKEN&token_type=Bearer")
        // Il primo redirect avvia davvero il collegamento (la chiamata di rete resta in coda).
        assertEquals(true, viewModel.state.value.aniList.isConnecting)

        // Stesso redirect riconsegnato da Android dopo un process death: il token nel frattempo
        // può essere scaduto, e ritentarlo produrrebbe un 401 che scollega un account valido.
        val restarted = createViewModel()
        restarted.onAniListAuthRedirect("access_token=$TOKEN&token_type=Bearer")

        assertFalse(restarted.state.value.aniList.isConnecting)
    }

    @Test
    fun `un redirect con token diverso viene elaborato`() {
        val viewModel = createViewModel()
        viewModel.onAniListAuthRedirect("access_token=$TOKEN&token_type=Bearer")

        val restarted = createViewModel()
        restarted.onAniListAuthRedirect("access_token=altro-token&token_type=Bearer")

        assertEquals(true, restarted.state.value.aniList.isConnecting)
    }

    @Test
    fun `il redirect gia elaborato riallinea comunque lo stato dell account`() {
        connectedStore().markAuthTokenHandled(TOKEN)
        val viewModel = createViewModel()
        assertNull("precondizione: nessun account in memoria", viewModel.state.value.aniList.viewer)

        // Il login era andato a buon fine (token e profilo sono su disco) ma questa istanza non
        // lo sa: il redirect ripetuto non deve rifare il login, però deve rimettere in pari.
        connectedStore().persistAccount(TOKEN, VIEWER)
        viewModel.onAniListAuthRedirect("access_token=$TOKEN&token_type=Bearer")

        assertFalse(viewModel.state.value.aniList.isConnecting)
        assertEquals(VIEWER, viewModel.state.value.aniList.viewer)
    }

    @Test
    fun `markAuthTokenHandled sopravvive alla disconnessione`() {
        val store = connectedStore()
        store.persistAccount(TOKEN, VIEWER)
        store.markAuthTokenHandled(TOKEN)

        store.clearAccount()

        assertNull(store.readToken())
        assertEquals(TOKEN, store.readHandledAuthToken())
    }

    private fun connectedStore(): AniListStore = AniListStore(prefs)

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    private companion object {
        const val TOKEN = "token-di-prova"
        val VIEWER = AniListViewer(id = 7, name = "lettore", scoreFormat = AniListScoreFormat.POINT_10)
    }
}
