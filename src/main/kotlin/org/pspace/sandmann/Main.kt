package org.pspace.sandmann

import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Main {

    val sandmannHomepage = URL("https://www.sandmann.de/filme/index.html")
    val targetDirectory = File("/Users/peach/Downloads/")

    @JvmStatic
    fun main(args: Array<String>) {

        val (todaysEpisodeDescriptor, title) = findLinkToDescriptorForTodaysEpisode()
        val bestVideoUrl = getHighestQualityVideoUrlFromJsonDescriptor(todaysEpisodeDescriptor)

        val fileName = makeFileNameFromTitle(title)

        val targetFile = File(targetDirectory, fileName)

        println("Dowloading video from '$bestVideoUrl' to '$targetFile'")

        downloadVideo(bestVideoUrl, targetFile)

        println("Done!")
    }

    private fun makeFileNameFromTitle(title: String): String {
        val sourceRemoved = title.replace(Regex("\\s+\\(Quelle.*\\)"), "")
        val sanitizedFilename = sanitizeFilename(sourceRemoved)
        val todayAsIso = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        return "$todayAsIso $sanitizedFilename"
    }

    fun sanitizeFilename(name: String): String {
        return name.replace("[/:]".toRegex(), "").trim()
    }

    private fun downloadVideo(url: URL, targetFile: File) {
        val readableByteChannel = Channels.newChannel(url.openStream())
        val outputStream = FileOutputStream(targetFile)
        outputStream.channel.transferFrom(readableByteChannel, 0, java.lang.Long.MAX_VALUE)
    }

    private fun getHighestQualityVideoUrlFromJsonDescriptor(jsonDescriptorUrl: URL): URL {

        val inputStreamReader = jsonDescriptorUrl.openStream().reader()

        val parser = JSONParser();
        val rootJson = parser.parse(inputStreamReader) as JSONObject;
        val mediaArray = rootJson.get("_mediaArray") as JSONArray
        val firstMedia = mediaArray.get(0) as JSONObject
        val videoEntries = firstMedia.get("_mediaStreamArray") as JSONArray
        val maxQualityVideo = videoEntries.maxBy { parseQuality(it) }
        val videoUrlString = (maxQualityVideo as JSONObject).get("_stream") as String

        return URL(videoUrlString)
    }

    private fun parseQuality(jsonObject: Any?): Long {
        val quality = (jsonObject as JSONObject).get("_quality")
        return when (quality) {
            is Long -> quality
            else -> Long.MIN_VALUE // quality='auto'
        }
    }

    private fun findLinkToDescriptorForTodaysEpisode(): Pair<URL, String> {
        val sandmannLandingPage = Jsoup.connect(sandmannHomepage.toString()).get()

        val linksToVideoPages = sandmannLandingPage.select("div[data-media-ref~=/filme]")

        val relevantLinksContainers = linksToVideoPages
                .distinctBy { mediaRef(it) }
                .filter { mediaRef(it).contains("automaticteaser.mediajsn.jsn") }

        assert(relevantLinksContainers.size == 1, { "Could not find unique link" })

        val firstLinkElement = relevantLinksContainers.first()

        val title = firstLinkElement.select("img").attr("title")
        val url = URL(sandmannHomepage, mediaRef(firstLinkElement))

        return (url to title)
    }

    private fun mediaRef(element: Element) = element.attr("data-media-ref")
}
