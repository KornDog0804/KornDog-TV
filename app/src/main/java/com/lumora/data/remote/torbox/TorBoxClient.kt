package com.lumora.data.remote.torbox

import com.lumora.BaseApplication
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject

class TorBoxClient(
    private val apiKey: String
) {
    private val client
        get() = BaseApplication.instance.okHttpClient

    data class CreatedTorrent(
        val id: Int,
        val hash: String?,
        val name: String?
    )

    data class TorrentFile(
        val id: Int,
        val name: String,
        val size: Long?
    )

    data class TorrentState(
        val id: Int,
        val name: String?,
        val downloadState: String?,
        val progress: Double?,
        val files: List<TorrentFile>
    ) {
        val ready: Boolean
            get() {
                val s = downloadState.orEmpty().lowercase()
                return s in setOf(
                    "cached",
                    "completed",
                    "downloaded",
                    "finished",
                    "ready"
                )
            }
    }

    private fun authed(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")

    suspend fun checkCached(infoHash: String): Boolean {
        val url =
            "https://api.torbox.app/v1/api/torrents/checkcached?hash=$infoHash"

        val req = authed(url).get().build()

        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return false

            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)

            val data = root.opt("data")

            when (data) {
                is Boolean -> data
                is JSONObject -> {
                    data.optBoolean(infoHash, false) ||
                        data.optBoolean(infoHash.lowercase(), false) ||
                        data.optBoolean("cached", false)
                }
                else -> false
            }
        }
    }

    suspend fun createTorrent(magnet: String): CreatedTorrent {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet)
            .build()

        val req = authed(
            "https://api.torbox.app/v1/api/torrents/createtorrent"
        )
            .post(body)
            .build()

        return client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "TorBox create failed: HTTP ${response.code}"
                )
            }

            val root = JSONObject(raw)
            val data = root.optJSONObject("data")
                ?: throw IllegalStateException("TorBox returned no torrent data")

            val id =
                data.optInt("torrent_id", data.optInt("id", -1))

            if (id < 0) {
                throw IllegalStateException("TorBox returned no torrent id")
            }

            CreatedTorrent(
                id = id,
                hash = data.optString("hash").takeIf { it.isNotBlank() },
                name = data.optString("name").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun torrentState(id: Int): TorrentState {
        val req = authed(
            "https://api.torbox.app/v1/api/torrents/mylist?id=$id"
        )
            .get()
            .build()

        return client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "TorBox status failed: HTTP ${response.code}"
                )
            }

            val root = JSONObject(raw)
            val data = root.opt("data")

            val obj = when (data) {
                is JSONObject -> data
                is org.json.JSONArray ->
                    data.optJSONObject(0)
                        ?: throw IllegalStateException("TorBox torrent not found")
                else ->
                    throw IllegalStateException("TorBox returned no torrent")
            }

            val filesJson = obj.optJSONArray("files")
            val files = buildList {
                if (filesJson != null) {
                    for (i in 0 until filesJson.length()) {
                        val f = filesJson.optJSONObject(i) ?: continue

                        add(
                            TorrentFile(
                                id = f.optInt("id", f.optInt("file_id", i)),
                                name = f.optString(
                                    "name",
                                    f.optString("short_name", "file-$i")
                                ),
                                size = f.optLong("size", -1L)
                                    .takeIf { it >= 0L }
                            )
                        )
                    }
                }
            }

            TorrentState(
                id = obj.optInt("id", id),
                name = obj.optString("name")
                    .takeIf { it.isNotBlank() },
                downloadState =
                    obj.optString("download_state")
                        .ifBlank {
                            obj.optString("status")
                        }
                        .takeIf { it.isNotBlank() },
                progress =
                    obj.optDouble("progress", Double.NaN)
                        .takeUnless { it.isNaN() },
                files = files
            )
        }
    }

    suspend fun waitUntilReady(
        id: Int,
        onProgress: (TorrentState) -> Unit
    ): TorrentState {
        repeat(240) {
            val state = torrentState(id)
            onProgress(state)

            if (state.ready && state.files.isNotEmpty()) {
                return state
            }

            delay(5_000)
        }

        throw IllegalStateException(
            "TorBox did not become ready in time"
        )
    }

    suspend fun requestDownloadUrl(
        torrentId: Int,
        fileId: Int
    ): String {
        return okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.torbox.app")
            .addPathSegments(
                "v1/api/torrents/requestdl"
            )
            .addQueryParameter("token", apiKey)
            .addQueryParameter(
                "torrent_id",
                torrentId.toString()
            )
            .addQueryParameter(
                "file_id",
                fileId.toString()
            )
            .addQueryParameter(
                "redirect",
                "true"
            )
            .build()
            .toString()
    }
}
