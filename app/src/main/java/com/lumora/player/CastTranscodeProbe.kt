package com.lumora.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.Transformer
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
class CastTranscodeProbe(
    private val context: Context,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "CastTranscodeProbe"
        private val PROBE_LOG = File("/sdcard/Download/castprobe.log")
    }

    private fun probeLog(message: String) {
        runCatching {
            PROBE_LOG.appendText(
                "${System.currentTimeMillis()}: $message\n"
            )
        }
    }

    fun run(
        upstreamUrl: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        onStarted: (File) -> Unit = {},
        onFinished: (Result<File>) -> Unit
    ) {
        val output = File(
            context.cacheDir,
            "cast-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.mp4"
        )
        output.delete()

        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(headers)
            .apply {
                if (!userAgent.isNullOrBlank()) {
                    setUserAgent(userAgent)
                }
            }

        val mediaSourceFactory =
            DefaultMediaSourceFactory(dataSourceFactory)

        val decoderFactory =
            DefaultDecoderFactory.Builder(context)
                .setEnableDecoderFallback(true)
                .build()

        val assetLoaderFactory =
            ExoPlayerAssetLoader.Factory(
                context,
                decoderFactory,
                Clock.DEFAULT,
                mediaSourceFactory
            )

        val muxerFactory =
            InAppMuxer.Factory.Builder()
                .setOutputFragmentedMp4(true)
                .setFragmentDurationMs(2_000)
                .build()

        val mediaItem =
            MediaItem.Builder()
                .setUri(upstreamUrl)
                .build()

        val transformer =
            Transformer.Builder(context)
                .setAssetLoaderFactory(assetLoaderFactory)
                .setMuxerFactory(muxerFactory)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            probeLog(
                                "COMPLETED path=${output.absolutePath} bytes=${output.length()}"
                            )
                            Log.d(
                                TAG,
                                "Probe completed path=${output.absolutePath} " +
                                    "bytes=${output.length()}"
                            )

                            runCatching {
                                val exportFile =
                                    File("/sdcard/Download/lumora-cast-test.mp4")

                                output.copyTo(exportFile, overwrite = true)

                                probeLog(
                                    "EXPORTED path=${exportFile.absolutePath} bytes=${exportFile.length()}"
                                )
                                Log.i(
                                    TAG,
                                    "Probe exported path=${exportFile.absolutePath} " +
                                        "bytes=${exportFile.length()}"
                                )
                            }.onFailure { error ->
                                probeLog(
                                    "EXPORT_FAILED ${error.javaClass.simpleName}: ${error.message}"
                                )
                                Log.e(TAG, "Probe export failed", error)
                            }

                            onFinished(Result.success(output))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            val causeChain = buildString {
                                var current: Throwable? = exportException
                                var depth = 0

                                while (current != null && depth < 8) {
                                    if (depth > 0) append(" <- ")

                                    append(current.javaClass.simpleName)
                                    append(": ")
                                    append(current.message)

                                    current = current.cause
                                    depth++
                                }
                            }

                            probeLog(
                                "FAILED path=${output.absolutePath} " +
                                    "bytes=${output.length()} " +
                                    causeChain
                            )
                            Log.e(TAG, "Probe failed", exportException)
                            onFinished(Result.failure(exportException))
                        }
                    }
                )
                .build()

        probeLog("START url=$upstreamUrl")
        Log.d(
            TAG,
            "Starting full-length H264/AAC fragmented-MP4 transcode"
        )

        // Expose the stable output path before Transformer starts writing.
        // Cast can register this file immediately and wait for bytes.
        onStarted(output)

        transformer.start(mediaItem, output.absolutePath)
    }
}
