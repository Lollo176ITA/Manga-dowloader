package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun authorizationUrl(): String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token"

    /**
     * Estrae l'`access_token` dal fragment del redirect (`access_token=…&token_type=Bearer&…`).
     * `null` se il fragment manca o non contiene il token (es. autorizzazione negata).
     */
    fun extractAccessToken(fragment: String?): String? {
        val raw = fragment?.trim()?.takeIf(String::isNotBlank) ?: return null
        return raw.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "access_token" }
            ?.get(1)
            ?.takeIf(String::isNotBlank)
    }
}

/**
 * Persistenza dell'account AniList (token + profilo) e dei legami serie→media su
 * [SharedPreferences], nello stile degli altri store del progetto. Il token resta solo qui:
 * non finisce nei backup esportati.
 */
class AniListStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)

    fun readViewer(): AniListViewer? {
        val id = prefs.getInt(KEY_VIEWER_ID, -1).takeIf { it > 0 } ?: return null
        val name = prefs.getString(KEY_VIEWER_NAME, null)?.takeIf(String::isNotBlank) ?: return null
        return AniListViewer(
            id = id,
            name = name,
            scoreFormat = aniListScoreFormatFromText(prefs.getString(KEY_VIEWER_SCORE_FORMAT, null)),
        )
    }

    fun persistAccount(token: String, viewer: AniListViewer) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putInt(KEY_VIEWER_ID, viewer.id)
            putString(KEY_VIEWER_NAME, viewer.name)
            putString(KEY_VIEWER_SCORE_FORMAT, viewer.scoreFormat.name)
        }
    }

    /** Scollega l'account. I legami serie→media restano: tornano utili al prossimo login. */
    fun clearAccount() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_VIEWER_ID)
            remove(KEY_VIEWER_NAME)
            remove(KEY_VIEWER_SCORE_FORMAT)
        }
    }

    fun readTrackings(): Map<String, AniListTracking> {
        val raw = prefs.getString(KEY_TRACKINGS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, TrackingJson>>(raw)
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
        } catch (_: Exception) {
            emptyMap()
        }
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
        prefs.edit {
            putString(KEY_TRACKINGS_JSON, json.encodeToString(payload))
        }
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

    private companion object {
        const val KEY_TOKEN = "anilist_token"
        const val KEY_VIEWER_ID = "anilist_viewer_id"
        const val KEY_VIEWER_NAME = "anilist_viewer_name"
        const val KEY_VIEWER_SCORE_FORMAT = "anilist_viewer_score_format"
        const val KEY_TRACKINGS_JSON = "anilist_trackings_json"
    }
}
