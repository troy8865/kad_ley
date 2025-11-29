package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.InputStream

class CanliTV : MainAPI() {
    override var mainUrl              = "https://raw.githubusercontent.com/edanuralkan2346-boop/turkey-/refs/heads/main/webspor.m3u"
    override var name                 = "CanliTV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

    // -------------------------------
    // ANA SAYFA
    // -------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

        val groups = kanallar.items.groupBy { it.attributes["group-title"] }

        return newHomePageResponse(
            groups.map { group ->
                val title = group.key ?: ""
                val showList = group.value.map { kanal ->
                    val data = LoadData(
                        kanal.url.orEmpty(),
                        kanal.title.orEmpty(),
                        kanal.attributes["tvg-logo"].orEmpty(),
                        kanal.attributes["group-title"].orEmpty(),
                        kanal.attributes["tvg-country"].orEmpty()
                    ).toJson()

                    newLiveSearchResponse(
                        kanal.title.orEmpty(),
                        data,
                        type = TvType.Live
                    ) {
                        this.posterUrl = kanal.attributes["tvg-logo"].orEmpty()
                        this.lang = kanal.attributes["tvg-country"].orEmpty()
                    }
                }

                HomePageList(title, showList, isHorizontalImages = true)
            },
            hasNext = false
        )
    }

    // -------------------------------
    // ARAMA
    // -------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

        return kanallar.items
            .filter { it.title.orEmpty().lowercase().contains(query.lowercase()) }
            .map { kanal ->
                newLiveSearchResponse(
                    kanal.title.orEmpty(),
                    LoadData(
                        kanal.url.orEmpty(),
                        kanal.title.orEmpty(),
                        kanal.attributes["tvg-logo"].orEmpty(),
                        kanal.attributes["group-title"].orEmpty(),
                        kanal.attributes["tvg-country"].orEmpty()
                    ).toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = kanal.attributes["tvg-logo"].orEmpty()
                    this.lang = kanal.attributes["tvg-country"].orEmpty()
                }
            }
    }

    override suspend fun quickSearch(query: String) = search(query)

    // -------------------------------
    // KANAL DETAY SAYFASI
    // -------------------------------
    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)

        val nationLabel = if (loadData.group == "NSFW") {
            "⚠️🔞🔞🔞 » ${loadData.group} | ${loadData.nation} « 🔞🔞🔞⚠️"
        } else {
            "» ${loadData.group} | ${loadData.nation} «"
        }

        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

        val recommendations = kanallar.items
            .filter { it.attributes["group-title"] == loadData.group && it.title != loadData.title }
            .map { kanal ->
                newLiveSearchResponse(
                    kanal.title.orEmpty(),
                    LoadData(
                        kanal.url.orEmpty(),
                        kanal.title.orEmpty(),
                        kanal.attributes["tvg-logo"].orEmpty(),
                        kanal.attributes["group-title"].orEmpty(),
                        kanal.attributes["tvg-country"].orEmpty()
                    ).toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = kanal.attributes["tvg-logo"].orEmpty()
                    this.lang = kanal.attributes["tvg-country"].orEmpty()
                }
            }

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl = loadData.poster
            this.plot = nationLabel
            this.tags = listOf(loadData.group, loadData.nation)
            this.recommendations = recommendations
        }
    }

    // -------------------------------
    // LİNK OLUŞTURMA – HATA VEREN KISIM DÜZELTİLDİ!!!
    // -------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = fetchDataFromUrlOrJson(data)
        Log.d("IPTV", "loadData » $loadData")

        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
        val kanal = kanallar.items.first { it.url == loadData.url }

        Log.d("IPTV", "kanal » $kanal")

        // ----------------------------------------------------
        // THE FIX → ExtractorLink() kaldırıldı → newExtractorLink kullanılıyor
        // ----------------------------------------------------
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = loadData.title,
                url = loadData.url,
                referer = kanal.headers["referrer"] ?: "",
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = kanal.headers
            )
        )

        return true
    }

    // -------------------------------
    // JSON / URL ayırıcı
    // -------------------------------
    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val group: String,
        val nation: String
    )

    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        if (data.startsWith("{")) {
            return parseJson<LoadData>(data)
        }

        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
        val kanal = kanallar.items.first { it.url == data }

        return LoadData(
            kanal.url.orEmpty(),
            kanal.title.orEmpty(),
            kanal.attributes["tvg-logo"].orEmpty(),
            kanal.attributes["group-title"].orEmpty(),
            kanal.attributes["tvg-country"].orEmpty()
        )
    }
}

/* ------------------------------------------------------------
   Aşağıdaki kısım: playlist parser → aynen bırakıldı
------------------------------------------------------------ */

data class Playlist(val items: List<PlaylistItem> = emptyList())

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist = parseM3U(content.byteInputStream())

    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()

        if (!reader.readLine().startsWith("#EXTM3U"))
            throw PlaylistParserException.InvalidHeader()

        val items = mutableListOf<PlaylistItem>()
        var index = 0
        var line = reader.readLine()

        while (line != null) {
            if (line.isNotEmpty()) {
                when {
                    line.startsWith("#EXTINF") -> {
                        items.add(
                            PlaylistItem(
                                line.split(",").lastOrNull()?.replace("\"", "")?.trim(),
                                line.getAttributes()
                            )
                        )
                    }

                    line.startsWith("#EXTVLCOPT") -> {
                        val item = items[index]
                        val userAgent = item.userAgent ?: line.getTagValue("http-user-agent")
                        val referrer = line.getTagValue("http-referrer")

                        val headers = mutableMapOf<String, String>()
                        if (userAgent != null) headers["user-agent"] = userAgent
                        if (referrer != null) headers["referrer"] = referrer

                        items[index] = item.copy(userAgent = userAgent, headers = headers)
                    }

                    !line.startsWith("#") -> {
                        val item = items[index]
                        val url = line.substringBefore("|").trim()
                        val ua = line.getUrlParameter("user-agent")
                        val ref = line.getUrlParameter("referer")

                        val mergedHeaders =
                            if (ref != null) item.headers + mapOf("referrer" to ref)
                            else item.headers

                        items[index] = item.copy(
                            url = url,
                            headers = mergedHeaders,
                            userAgent = ua ?: item.userAgent
                        )
                        index++
                    }
                }
            }
            line = reader.readLine()
        }

        return Playlist(items)
    }

    private fun String.getAttributes(): Map<String, String> =
        replace(Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE), "")
            .replace("\"", "")
            .trim()
            .split(",").first()
            .split(" ")
            .mapNotNull {
                val p = it.split("=")
                if (p.size == 2) p[0] to p[1].trim() else null
            }
            .toMap()

    private fun String.getTagValue(key: String): String? =
        Regex("$key=(.*)", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groups?.get(1)
            ?.value
            ?.replace("\"", "")
            ?.trim()

    private fun String.getUrlParameter(key: String): String? =
        Regex("$key=(\\w[^&]*)", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groups?.get(1)?.value
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}
