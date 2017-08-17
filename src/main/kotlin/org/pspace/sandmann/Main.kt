package org.pspace.sandmann

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Main {

    val SANDMANN_HOMEPAGE = URL("https://www.sandmann.de/filme/index.html")

    val BUCKET_NAME = "sandmann-repo"

    @JvmStatic
    fun main(args: Array<String>) {

        val (todaysEpisodeDescriptor, title) = findLinkToDescriptorForTodaysEpisode()
        val bestVideoUrl = getHighestQualityVideoUrlFromJsonDescriptor(todaysEpisodeDescriptor)

        uploadVideoToS3(
                targetFileName = makeFileNameFromTitle(title),
                inputStream = bestVideoUrl.openStream()
        )
    }

    private fun makeFileNameFromTitle(title: String): String {
        val sourceRemoved = title.replace(Regex("\\s+\\(Quelle.*\\)"), "")
        val sanitizedFilename = sanitizeFilename(sourceRemoved)
        val todayAsIso = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        return "$todayAsIso $sanitizedFilename.mp4"
    }

    fun sanitizeFilename(name: String): String {
        return name.replace("[/:]".toRegex(), "").trim()
    }

    private fun uploadVideoToS3(targetFileName: String, inputStream: InputStream) {
        println("Upoading to AWS")

        val metadata = ObjectMetadata()
        metadata.contentType = "video/mp4"
        metadata.contentLanguage = "de"

        getS3Client().putObject(
                BUCKET_NAME,
                targetFileName,
                BufferedInputStream(inputStream),
                metadata
        );

        println("Done uploading")
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
        val sandmannLandingPage = Jsoup.connect(SANDMANN_HOMEPAGE.toString()).get()

        val linksToVideoPages = sandmannLandingPage.select("div[data-media-ref~=/filme]")

        val relevantLinksContainers = linksToVideoPages
                .distinctBy { mediaRef(it) }
                .filter { mediaRef(it).contains("automaticteaser.mediajsn.jsn") }

        assert(relevantLinksContainers.size == 1, { "Could not find unique link" })

        val firstLinkElement = relevantLinksContainers.first()

        val title = firstLinkElement.select("img").attr("title")
        val url = URL(SANDMANN_HOMEPAGE, mediaRef(firstLinkElement))

        return (url to title)
    }

    private fun mediaRef(element: Element) = element.attr("data-media-ref")

    private fun getS3Client(): AmazonS3 {
        return AmazonS3ClientBuilder
                .standard()
                .withCredentials(EnvironmentVariableCredentialsProvider())
                .withRegion(Regions.EU_CENTRAL_1)
                .build()
    }
}
