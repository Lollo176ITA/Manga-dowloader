package com.lorenzo.mangadownloader

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MangaNetworkClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Document {
        return Jsoup.parse(fetchString(url, headers), url)
    }

    fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", headers["Accept-Language"] ?: "it,en;q=0.8")
            .apply {
                headers.forEach { (name, value) ->
                    if (name != "Accept" && name != "Accept-Language") {
                        header(name, value)
                    }
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} su $url")
            }
            return response.body?.string() ?: throw IOException("Risposta vuota da $url")
        }
    }

    fun fetchBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                referer?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { header("Referer", it) }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} scaricando $url")
            }
            return response.body?.bytes() ?: throw IOException("Immagine vuota da $url")
        }
    }

    fun absolutize(baseUrl: String, value: String): String {
        return URI(baseUrl).resolve(value).toString()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
