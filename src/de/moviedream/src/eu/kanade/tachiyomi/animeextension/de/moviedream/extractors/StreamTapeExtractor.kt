package eu.kanade.tachiyomi.animeextension.de.moviedream.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String): Video? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(document.getElementById('norobotlink'))")
            .firstOrNull()?.data()?.substringAfter("document.getElementById('norobotlink').innerHTML = '")
            ?: return null
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")
        return Video(url, quality, videoUrl)
    }
}
