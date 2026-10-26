package com.lorenzo.mangadownloader.data.anilist

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lorenzo.mangadownloader.data.store.readJson
import com.lorenzo.mangadownloader.data.store.writeJson
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Dominio del **tracking AniList**: l'utente collega il proprio account (OAuth implicit grant,
 * vedi [AniListAuth]) e l'app scrive sulla sua lista manga lo stato e il progresso di lettura.
 * Il legame serie-dell'app → media AniList è per `identityKey` (vedi
 * [MangaSourceCatalog.identityKey]) ed è persistito da [AniListStore].
 */

/** Stato di un'entry nella lista AniList ([MediaListStatus] dell'API, stessi nomi). */
enum class AniListListStatus(val label: String) {
    CURRENT("In lettura"),
    PLANNING("Da leggere"),
    COMPLETED("Completato"),
    PAUSED("In pausa"),
    DROPPED("Abbandonato"),
    REPEATING("In rilettura"),
}

/** Mappa il testo grezzo dell'API su [AniListListStatus]; `null` se assente o ignoto. */
fun aniListStatusFromText(raw: String?): AniListListStatus? {
    val text = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
    return AniListListStatus.entries.firstOrNull { it.name == text }
}

/**
 * Formato voto dell'account AniList ([ScoreFormat] dell'API). Determina scala e passo dello
 * slider voto: il valore inviato a `SaveMediaListEntry(score:)` è già in questo formato.
 */
enum class AniListScoreFormat(val maxValue: Float, val decimal: Boolean) {
    POINT_100(100f, false),
    POINT_10_DECIMAL(10f, true),
    POINT_10(10f, false),
    POINT_5(5f, false),
    POINT_3(3f, false);

    /** Voto formattato per la UI (es. "8", "8.5", "85"). */
    fun displayValue(score: Double): String {
        return if (decimal) {
            String.format(Locale.US, "%.1f", score)
        } else {
            score.toInt().toString()
        }
    }
}

/** Mappa il testo grezzo dell'API su [AniListScoreFormat]; default prudente [POINT_10]. */
fun aniListScoreFormatFromText(raw: String?): AniListScoreFormat {
    val text = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank)
        ?: return AniListScoreFormat.POINT_10
    return AniListScoreFormat.entries.firstOrNull { it.name == text }
        ?: AniListScoreFormat.POINT_10
}

/** L'utente AniList autenticato. Presente nello stato ⇔ account collegato. */
data class AniListViewer(
    val id: Int,
    val name: String,
    val scoreFormat: AniListScoreFormat,
)

/** Entry della lista utente come la riporta l'API (voto nel formato dell'account, 0 = nessuno). */
data class AniListListEntry(
    val status: AniListListStatus?,
    val progress: Int,
    val score: Double?,
)

/** Media AniList + (eventuale) entry dell'utente: serve a seedare il tracking al collegamento. */
data class AniListMediaEntry(
    val mediaId: Int,
    val totalChapters: Int?,
    val entry: AniListListEntry?,
)

/**
 * Legame persistito tra una serie dell'app e un media AniList, con l'ultimo stato noto
 * dell'entry. [pendingProgress] è un progresso registrato a fine capitolo ma non ancora
 * accettato dal server (offline/errore): viene ri-spinto al prossimo avvio o capitolo letto.
 */
data class AniListTracking(
    val mediaId: Int,
    val title: String,
    val totalChapters: Int? = null,
    val status: AniListListStatus? = null,
    val progress: Int = 0,
    val score: Double? = null,
    val pendingProgress: Int? = null,
) {
    fun siteUrl(): String = "https://anilist.co/manga/$mediaId"
}

/**
 * OAuth **implicit grant** di AniList: il browser apre [authorizationUrl], l'utente autorizza,
 * AniList redirige su `mangapp://anilist-auth#access_token=…` (intent-filter di MainActivity)
 * e [extractAccessToken] estrae il token dal fragment. Nessun client secret nell'app: il
 * [CLIENT_ID] è pubblico per design. Il token vale circa un anno.
 */
object AniListAuth {
    /**
     * ID del client API creato su https://anilist.co/settings/developer con redirect URL
     * `mangapp://anilist-auth`. È pubblico per design (l'implicit grant non usa il client
     * secret, che infatti NON va incluso nell'app).
     */
    const val CLIENT_ID = "43385"

    const val REDIRECT_SCHEME = "mangapp"
    const val REDIRECT_HOST = "anilist-auth"

    fun isConfigured(): Boolean = CLIENT_ID.isNotBlank()

    /** [state] torna indietro nel redirect: vedi [newLoginState]. */
    fun authorizationUrl(state: String): String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token&state=$state"

    /**
     * Codice casuale e monouso che lega il redirect al login avviato dall'app. Il redirect è
     * un link `mangapp://` che chiunque può aprire: senza questo codice un link costruito ad
     * arte collegherebbe l'account di un altro, che da quel momento riceverebbe progressi e
     * preferiti.
     */
    fun newLoginState(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Estrae l'`access_token` dal fragment del redirect (`access_token=…&token_type=Bearer&…`).
     * `null` se il fragment manca o non contiene il token (es. autorizzazione negata).
     */
    fun extractAccessToken(fragment: String?): String? = fragmentParam(fragment, "access_token")

    /** Lo `state` riportato dal redirect, `null` se manca. */
    fun extractState(fragment: String?): String? = fragmentParam(fragment, "state")

    private fun fragmentParam(fragment: String?, name: String): String? {
        val raw = fragment?.trim()?.takeIf(String::isNotBlank) ?: return null
        return raw.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.takeIf(String::isNotBlank)
    }

    private val secureRandom = SecureRandom()
}

/**
 * Persistenza dell'account AniList (token + profilo) e dei legami serie→media su
 * [SharedPreferences], nello stile degli altri store del progetto. Il token resta solo qui:
 * non finisce nei backup esportati.
 *
 * Token, profilo e login in corso stanno in [accountPrefs] ([ACCOUNT_PREFS_NAME]), un file a
 * parte escluso dal backup di Android e dal trasferimento tra telefoni
 * (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`). I legami serie→media
 * restano in [prefs] e quindi nel backup: tornano utili al prossimo login.
 */
class AniListStore(
    private val prefs: SharedPreferences,
    private val accountPrefs: SharedPreferences,
) {

    init {
        // Le versioni precedenti tenevano qui in chiaro l'ultimo token del redirect, per non
        // rielaborarlo dopo un process death: ora lo impedisce lo `state` monouso.
        if (prefs.contains(KEY_LEGACY_HANDLED_AUTH_TOKEN)) {
            prefs.edit { remove(KEY_LEGACY_HANDLED_AUTH_TOKEN) }
        }
        migrateAccountToOwnFile()
    }

    /** Una tantum: account salvato dalle versioni precedenti nel file comune, quindi nel backup. */
    private fun migrateAccountToOwnFile() {
        val legacy = prefs.all.filterKeys { it in ACCOUNT_KEYS }
        if (legacy.isEmpty()) return
        accountPrefs.edit(commit = true) {
            legacy.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }
        prefs.edit { legacy.keys.forEach(::remove) }
    }

    fun readToken(): String? = accountPrefs.getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)

    /**
     * `state` dell'ultimo login avviato e non ancora concluso (vedi [AniListAuth.newLoginState]).
     * Sta su disco perché il sistema può chiudere l'app mentre l'utente è nel browser.
     */
    fun readPendingLoginState(): String? =
        accountPrefs.getString(KEY_PENDING_LOGIN_STATE, null)?.takeIf(String::isNotBlank)

    fun savePendingLoginState(state: String) {
        accountPrefs.edit { putString(KEY_PENDING_LOGIN_STATE, state) }
    }

    fun clearPendingLoginState() {
        accountPrefs.edit { remove(KEY_PENDING_LOGIN_STATE) }
    }

    fun readViewer(): AniListViewer? {
        val id = accountPrefs.getInt(KEY_VIEWER_ID, -1).takeIf { it > 0 } ?: return null
        val name = accountPrefs.getString(KEY_VIEWER_NAME, null)?.takeIf(String::isNotBlank) ?: return null
        return AniListViewer(
            id = id,
            name = name,
            scoreFormat = aniListScoreFormatFromText(accountPrefs.getString(KEY_VIEWER_SCORE_FORMAT, null)),
        )
    }

    fun persistAccount(token: String, viewer: AniListViewer) {
        accountPrefs.edit {
            putString(KEY_TOKEN, token)
            putInt(KEY_VIEWER_ID, viewer.id)
            putString(KEY_VIEWER_NAME, viewer.name)
            putString(KEY_VIEWER_SCORE_FORMAT, viewer.scoreFormat.name)
        }
    }

    /** Scollega l'account. I legami serie→media restano: tornano utili al prossimo login. */
    fun clearAccount() {
        accountPrefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_VIEWER_ID)
            remove(KEY_VIEWER_NAME)
            remove(KEY_VIEWER_SCORE_FORMAT)
        }
    }

    fun readTrackings(): Map<String, AniListTracking> {
        val raw = prefs.readJson<Map<String, TrackingJson>>(KEY_TRACKINGS_JSON, emptyMap())
            .filterValues { it.mediaId > 0 }
            .mapValues { (_, entry) ->
                AniListTracking(
                    mediaId = entry.mediaId,
                    title = entry.title,
                    totalChapters = entry.totalChapters,
                    status = aniListStatusFromText(entry.status),
                    progress = entry.progress,
                    score = entry.score,
                    pendingProgress = entry.pendingProgress,
                )
            }
        // Migrazione lazy: le chiavi storiche erano identityKey per-fonte (`sourceId::url`);
        // il mediaId nel valore permette di ri-ancorarle alla SeriesKey canonica.
        // Duplicati (stessa serie collegata da più fonti): vince il progress più alto.
        var migratedAny = false
        val migrated = LinkedHashMap<String, AniListTracking>()
        raw.forEach { (key, tracking) ->
            val newKey = if (
                key.startsWith(SeriesIdentity.ANILIST_PREFIX) ||
                key.startsWith(SeriesIdentity.TITLE_PREFIX)
            ) {
                key
            } else {
                migratedAny = true
                SeriesIdentity.keyForAniList(tracking.mediaId)
            }
            val existing = migrated[newKey]
            if (existing == null || tracking.progress > existing.progress) {
                migrated[newKey] = tracking
            }
        }
        if (migratedAny) {
            persistTrackings(migrated)
        }
        return migrated
    }

    fun persistTrackings(trackings: Map<String, AniListTracking>) {
        val payload = trackings.mapValues { (_, tracking) ->
            TrackingJson(
                mediaId = tracking.mediaId,
                title = tracking.title,
                totalChapters = tracking.totalChapters,
                status = tracking.status?.name,
                progress = tracking.progress,
                score = tracking.score,
                pendingProgress = tracking.pendingProgress,
            )
        }
        prefs.writeJson(KEY_TRACKINGS_JSON, payload)
    }

    /** Forma su disco di un legame serie→media. */
    @Serializable
    private data class TrackingJson(
        val mediaId: Int = 0,
        val title: String = "",
        val totalChapters: Int? = null,
        val status: String? = null,
        val progress: Int = 0,
        val score: Double? = null,
        val pendingProgress: Int? = null,
    )

    companion object {
        /** Va tenuto allineato con le esclusioni in data_extraction_rules.xml e backup_rules.xml. */
        const val ACCOUNT_PREFS_NAME = "anilist_account_prefs"

        private const val KEY_TOKEN = "anilist_token"
        private const val KEY_LEGACY_HANDLED_AUTH_TOKEN = "anilist_handled_auth_token"
        private const val KEY_PENDING_LOGIN_STATE = "anilist_pending_login_state"
        private const val KEY_VIEWER_ID = "anilist_viewer_id"
        private const val KEY_VIEWER_NAME = "anilist_viewer_name"
        private const val KEY_VIEWER_SCORE_FORMAT = "anilist_viewer_score_format"
        private const val KEY_TRACKINGS_JSON = "anilist_trackings_json"
        private val ACCOUNT_KEYS =
            setOf(KEY_TOKEN, KEY_VIEWER_ID, KEY_VIEWER_NAME, KEY_VIEWER_SCORE_FORMAT)
    }
}
