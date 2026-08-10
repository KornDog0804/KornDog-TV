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
        private const val TEST_DURATION_MS = 60_000L
    }

    fun run(
        upstreamUrl: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        onFinished: (Result<File>) -> Unit
    ) {
        val output = File(context.cacheDir, "cast-test.mp4")
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
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(TEST_DURATION_MS)
                        .build()
                )
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
                            Log.d(
                                TAG,
                                "Probe completed path=${output.absolutePath} " +
                                    "bytes=${output.length()}"
                            )
                            onFinished(Result.success(output))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Probe failed", exportException)
                            onFinished(Result.failure(exportException))
                        }
                    }
                )
                .build()

        Log.d(
            TAG,
            "Starting 60-second H264/AAC fragmented-MP4 probe"
        )

        transformer.start(mediaItem, output.absolutePath)
    }
}
