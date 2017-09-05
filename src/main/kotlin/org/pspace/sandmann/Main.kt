package org.pspace.sandmann

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
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
import java.io.OutputStream
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class Main : RequestStreamHandler {

    val SANDMANN_HOMEPAGE = URL("https://www.sandmann.de/filme/index.html")

    val BUCKET_NAME = "sandmann-repo"

    override fun handleRequest(input: InputStream?, output: OutputStream?, context: Context?) {
        val (todaysEpisodeDescriptor, title) = findLinkToDescriptorForTodaysEpisode()
        val bestVideoUrl = getHighestQualityVideoUrlFromJsonDescriptor(todaysEpisodeDescriptor)

        uploadVideoToS3(
                targetFileName = makeFileNameFromTitle(title),
                sourceUrl = bestVideoUrl
        )
    }

    private fun makeFileNameFromTitle(title: String): String {
        val sourceRemoved = title.replace(Regex("\\s+\\(Quelle.*\\)"), "")
        val sanitizedFilename = sanitizeFilename(sourceRemoved)
        val todayAsIso = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        return "$todayAsIso $sanitizedFilename.mp4"
    }

    private fun sanitizeFilename(name: String): String {
        return name
                .replace("[/]".toRegex(), "")
                .replace("[:]".toRegex(), " -")
                .trim()
    }

    private fun uploadVideoToS3(targetFileName: String, sourceUrl: URL) {
        println("Uploading to AWS")

        val urlConnection = sourceUrl.openConnection()

        val metadata = ObjectMetadata()
        metadata.contentType = urlConnection.contentType
        metadata.contentLength = urlConnection.contentLengthLong
        metadata.contentLanguage = "de"

        println("Content-type: ${urlConnection.contentType}")
        println("Content-length: ${urlConnection.contentLengthLong / 1024 / 1024} MB")

        val inputStream = BufferedInputStream(urlConnection.getInputStream())

        getS3Client().putObject(
                BUCKET_NAME,
                targetFileName,
                inputStream,
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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Main().handleRequest(null, null, null)
        }
    }
}
