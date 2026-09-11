package com.lumora.data.remote.realdebrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class RealDebridClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient()
) {

    data class TorrentFile(
        val id: Int,
        val path: String,
        val bytes: Long,
        val selected: Boolean
    )

    data class TorrentInfo(
        val id: String,
        val status: String,
        val files: List<TorrentFile>,
        val links: List<String>
    )

    suspend fun addMagnet(
        magnet: String
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("magnet", magnet)
            .build()

        val request = requestBuilder(
            "https://api.real-debrid.com/rest/1.0/torrents/addMagnet"
        )
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Real-Debrid add magnet failed: HTTP ${response.code}"
                )
            }

            JSONObject(text)
                .optString("id")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Real-Debrid returned no torrent id"
                )
        }
    }

    suspend fun getTorrentInfo(
        id: String
    ): TorrentInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder(
            "https://api.real-debrid.com/rest/1.0/torrents/info/$id"
        )
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Real-Debrid info failed: HTTP ${response.code}"
                )
            }

            val root = JSONObject(text)

            val filesArray = root.optJSONArray("files")
            val files = buildList {
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val f = filesArray.optJSONObject(i) ?: continue

                        add(
                            TorrentFile(
                                id = f.optInt("id"),
                                path = f.optString("path"),
                                bytes = f.optLong("bytes"),
                                selected = f.optInt("selected") == 1
                            )
                        )
                    }
                }
            }

            val linksArray = root.optJSONArray("links")
            val links = buildList {
                if (linksArray != null) {
                    for (i in 0 until linksArray.length()) {
                        val link = linksArray.optString(i)
                        if (link.isNotBlank()) {
                            add(link)
                        }
                    }
                }
            }

            TorrentInfo(
                id = root.optString("id", id),
                status = root.optString("status"),
                files = files,
                links = links
            )
        }
    }

    suspend fun selectFiles(
        id: String,
        fileIds: List<Int>
    ) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("files", fileIds.joinToString(","))
            .build()

        val request = requestBuilder(
            "https://api.real-debrid.com/rest/1.0/torrents/selectFiles/$id"
        )
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (
                !response.isSuccessful &&
                response.code != 202
            ) {
                throw IllegalStateException(
                    "Real-Debrid select files failed: HTTP ${response.code}"
                )
            }
        }
    }

    suspend fun waitForLinks(
        id: String,
        attempts: Int = 30,
        delayMs: Long = 1000L
    ): TorrentInfo {
        repeat(attempts) {
            val info = getTorrentInfo(id)

            if (info.links.isNotEmpty()) {
                return info
            }

            delay(delayMs)
        }

        throw IllegalStateException(
            "Real-Debrid did not become ready"
        )
    }

    suspend fun unrestrict(
        link: String
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("link", link)
            .build()

        val request = requestBuilder(
            "https://api.real-debrid.com/rest/1.0/unrestrict/link"
        )
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Real-Debrid unrestrict failed: HTTP ${response.code}"
                )
            }

            JSONObject(text)
                .optString("download")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Real-Debrid returned no playable link"
                )
        }
    }

    private fun requestBuilder(
        url: String
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
}
