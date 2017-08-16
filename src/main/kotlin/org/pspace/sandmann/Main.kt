package org.pspace.sandmann

import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Main {

    val sandmannHomepage = URL("https://www.sandmann.de/filme/index.html")
    val targetFile = File("/Users/peach/Downloads/${todayAsIso()}.mp4")

    @JvmStatic
    fun main(args: Array<String>) {

        val todaysEpisodeDescriptor = findLinkToDescriptorForTodaysEpisode()
        val bestVideoUrl = getHighestQualityVideoUrlFromJsonDescriptor(todaysEpisodeDescriptor)

        downloadVideo(bestVideoUrl, targetFile)

        println("Done!")
    }

    private fun todayAsIso() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)

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

    private fun findLinkToDescriptorForTodaysEpisode(): URL {
        val sandmannLandingPage = Jsoup.connect(sandmannHomepage.toString()).get()

        val linksToVideoPages = sandmannLandingPage.select("div[data-media-ref~=/filme]")

        val relevantLinks = linksToVideoPages
                .map { it.attr("data-media-ref") }
                .distinct()
                .filter { it.contains("automaticteaser.mediajsn.jsn") }

        assert(relevantLinks.size == 1, { "Could not find unique link" })

        return URL(sandmannHomepage, relevantLinks.first())
    }
}
