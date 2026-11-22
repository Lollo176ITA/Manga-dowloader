package com.lorenzo.mangadownloader.data.sources

import android.content.Context
import androidx.core.content.edit
import com.lorenzo.mangadownloader.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Una fonte è stata messa in manutenzione da `sources.json`: l'app non la interroga. Resta una
 * [IOException] così ricerca, preferiti e download la trattano come una fonte che non risponde
 * (e ripiegano sulle altre), ma non va ritentata: non passerà al giro dopo.
 */
class SourceMaintenanceException(message: String) : IOException(message)

@Serializable
private data class RemoteSourceEntry(
    val domain: String? = null,
    val maintenance: String? = null,
)

@Serializable
private data class RemoteSourceFile(
    val sources: Map<String, RemoteSourceEntry> = emptyMap(),
)

/**
 * Ciò che `sources.json` cambia rispetto a quanto è scritto nell'APK.
 *
 * I domini si traducono **solo in rete**: URL salvati (preferiti, download, cronologia) e
 * riconoscimento degli URL restano sul dominio di partenza di ogni fonte
 * ([MangaSourceSpec.networkDomain]). Le richieste partono verso quello nuovo ([liveHost]) e le
 * pagine ricevute tornano alla forma salvata ([toSavedDomains]) prima di essere lette.
 *
 * @property liveDomains dominio di partenza → dominio attuale, solo per le fonti traslocate.
 * @property maintenance id della fonte → messaggio per l'utente.
 */
class SourceOverrides internal constructor(
    val liveDomains: Map<String, String>,
    val maintenance: Map<String, String>,
    private val maintenanceByDomain: Map<String, String>,
) {
    /** Host a cui mandare davvero la richiesta, o `null` se [host] non è di una fonte traslocata. */
    fun liveHost(host: String): String? {
        val lowered = host.lowercase()
        for ((saved, live) in liveDomains) {
            if (lowered == saved) return live
            if (lowered.endsWith(".$saved")) return lowered.removeSuffix(saved) + live
        }
        return null
    }

    /** Riporta i link di una pagina letta dal dominio nuovo alla forma con cui sono salvati. */
    fun toSavedDomains(text: String): String {
        var result = text
        for ((saved, live) in liveDomains) {
            result = result.replace(live, saved, ignoreCase = true)
        }
        return result
    }

    /** Messaggio completo ("<Fonte> è in manutenzione: …") se [host] è di una fonte ferma. */
    fun maintenanceMessageForHost(host: String): String? {
        val lowered = host.lowercase()
        return maintenanceByDomain.entries
            .firstOrNull { (domain, _) -> lowered == domain || lowered.endsWith(".$domain") }
            ?.value
    }

    companion object {
        val NONE = SourceOverrides(emptyMap(), emptyMap(), emptyMap())
    }
}

private val remoteSourceJson = Json { ignoreUnknownKeys = true }
private val plainDomain = Regex("""[a-z0-9-]+(\.[a-z0-9-]+)+""")

/**
 * Legge `sources.json`. Lancia se non è JSON valido (una pagina d'errore al posto del file):
 * così chi chiama tiene la copia precedente. Le singole voci sbagliate invece si ignorano:
 * contano solo le fonti del catalogo, e un dominio deve essere un nome di dominio e basta.
 */
internal fun parseSourceOverrides(
    raw: String,
    specs: List<MangaSourceSpec> = MangaSourceCatalog.specs,
): SourceOverrides {
    val file = remoteSourceJson.decodeFromString<RemoteSourceFile>(raw)
    val liveDomains = linkedMapOf<String, String>()
    val maintenance = linkedMapOf<String, String>()
    val maintenanceByDomain = linkedMapOf<String, String>()
    for (spec in specs) {
        val entry = file.sources[spec.descriptor.id] ?: continue
        val live = entry.domain?.trim()?.lowercase()
        if (live != null && live != spec.networkDomain && plainDomain.matches(live)) {
            liveDomains[spec.networkDomain] = live
        }
        val message = entry.maintenance?.trim()?.takeIf(String::isNotBlank)
        if (message != null) {
            maintenance[spec.descriptor.id] = message
            maintenanceByDomain[spec.networkDomain] =
                "${spec.descriptor.displayName} è in manutenzione: $message"
        }
    }
    return SourceOverrides(liveDomains, maintenance, maintenanceByDomain)
}

/**
 * Copia locale di `sources.json`, il file nel repo (accanto a `version.properties`) con cui si
 * sposta una fonte su un dominio nuovo o la si mette in manutenzione senza rilasciare l'app.
 * Solo dati: mai codice né selettori.
 *
 * La copia vive in un file di preferenze suo, minuscolo, e si carica al primo uso.
 */
object RemoteSourceConfig {
    private const val PREFS_NAME = "remote_source_config"
    private const val KEY_RAW = "raw_json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded: SourceOverrides? = null

    /** Da chiamare in `Application.onCreate`. Non legge nulla: il file si apre al primo uso. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    val current: SourceOverrides
        get() = loaded ?: synchronized(this) {
            loaded ?: readCached().also { loaded = it }
        }

    /** Solo per i test: imposta (o azzera, con `null`) ciò che [current] restituisce. */
    internal fun replaceForTest(overrides: SourceOverrides?) {
        loaded = overrides
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readCached(): SourceOverrides {
        val raw = prefs()?.getString(KEY_RAW, null) ?: return SourceOverrides.NONE
        return try {
            parseSourceOverrides(raw)
        } catch (_: Exception) {
            SourceOverrides.NONE
        }
    }

    /**
     * Riscarica il file se la copia ha più di sei ore (o sempre, con [force]). Un errore di
     * rete o un file illeggibile lasciano la copia di prima: non deve mai rompere l'app.
     */
    suspend fun refresh(httpClient: OkHttpClient, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong(KEY_FETCHED_AT, 0L)
        if (!force && age in 0 until REFRESH_INTERVAL_MS) return@withContext
        try {
            val request = Request.Builder().url(configUrl()).header("Cache-Control", "no-cache").build()
            val raw = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.string()
            }
            val parsed = parseSourceOverrides(raw)
            prefs.edit {
                putString(KEY_RAW, raw)
                putLong(KEY_FETCHED_AT, now)
            }
            loaded = parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Si riprova al prossimo avvio.
        }
    }

    /** `sources.json` sta nella stessa cartella di `version.properties`, su `main`. */
    private fun configUrl(): String =
        BuildConfig.UPDATE_CONFIG_URL.substringBeforeLast('/') + "/sources.json"
}

/**
 * Manda le richieste verso i domini attuali delle fonti traslocate (anche copertine e pagine
 * caricate da Coil, che condivide il client), aggiornando anche il `Referer`.
 */
class SourceDomainInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val overrides = RemoteSourceConfig.current
        if (overrides.liveDomains.isEmpty()) return chain.proceed(request)

        val liveHost = overrides.liveHost(request.url.host)
        val referer = request.header("Referer")
        val liveReferer = referer?.let(overrides::liveUrlOrNull)
        if (liveHost == null && liveReferer == null) return chain.proceed(request)

        val builder = request.newBuilder()
        if (liveHost != null) builder.url(request.url.newBuilder().host(liveHost).build())
        if (liveReferer != null) builder.header("Referer", liveReferer)
        return chain.proceed(builder.build())
    }
}

private fun SourceOverrides.liveUrlOrNull(url: String): String? {
    val host = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(1) ?: return null
    val live = liveHost(host) ?: return null
    return url.replaceFirst(host, live)
}
