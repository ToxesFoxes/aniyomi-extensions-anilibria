package eu.kanade.tachiyomi.animeextension.all.lmanime

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class LMAnime : AnimeStream(
    "all",
    "LMAnime",
    "https://lmanime.com",
) {
    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("144p", "288p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        val allowed = preferences.getStringSet(PREF_ALLOWED_LANGS_KEY, PREF_ALLOWED_LANGS_DEFAULT)!!
        return items
            .filter { element ->
                val text = element.text()
                allowed.any { it in text }
            }.parallelMap {
                val language = it.text().substringBefore(" ")
                val url = getHosterUrl(it)
                getVideoList(url, language)
            }.flatten()
    }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name -"
        return when {
            "ok.ru" in url ->
                OkruExtractor(client).videosFromUrl(url, prefix)
            "dailymotion.com" in url ->
                DailymotionExtractor(client, headers).videosFromUrl(url, "Dailymotion ($name)")
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences
        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_ENTRIES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val allowedPref = MultiSelectListPreference(screen.context).apply {
            key = PREF_ALLOWED_LANGS_KEY
            title = PREF_ALLOWED_LANGS_TITLE
            entries = PREF_ALLOWED_LANGS_ENTRIES
            entryValues = PREF_ALLOWED_LANGS_ENTRIES
            setDefaultValue(PREF_ALLOWED_LANGS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }

        screen.addPreference(langPref)
        screen.addPreference(allowedPref)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(lang, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "pref_language"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "English"
        private val PREF_LANG_ENTRIES = arrayOf(
            "English",
            "Español",
            "Indonesian",
            "Portugués",
            "Türkçe",
            "العَرَبِيَّة",
            "ไทย",
        )

        private const val PREF_ALLOWED_LANGS_KEY = "pref_allowed_languages"
        private const val PREF_ALLOWED_LANGS_TITLE = "Allowed languages to fetch videos"
        private val PREF_ALLOWED_LANGS_ENTRIES = PREF_LANG_ENTRIES
        private val PREF_ALLOWED_LANGS_DEFAULT = PREF_ALLOWED_LANGS_ENTRIES.toSet()
    }
}
