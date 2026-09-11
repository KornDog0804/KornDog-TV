package com.lumora.data.remote.premiumize

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class PremiumizeClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    data class PremiumizeFile(
        val path: String,
        val size: Long,
        val link: String
    )

    suspend fun directDownload(
        source: String
    ): List<PremiumizeFile> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("src", source)
            .build()

        val request = Request.Builder()
            .url("https://www.premiumize.me/api/transfer/directdl")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Premiumize failed: HTTP ${response.code}"
                )
            }

            val root = JSONObject(text)

            if (root.optString("status") != "success") {
                throw IllegalStateException(
                    root.optString("message", "Premiumize failed")
                )
            }

            val content = root.optJSONArray("content")
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue

                    val link = item.optString("link")
                    if (link.isBlank()) continue

                    add(
                        PremiumizeFile(
                            path = item.optString("path"),
                            size = item.optLong("size"),
                            link = link
                        )
                    )
                }
            }
        }
    }
}
