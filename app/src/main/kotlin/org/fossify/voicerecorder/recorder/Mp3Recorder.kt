package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.AvatarSessionHolder
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class Mp3Recorder(val context: Context) : Recorder {
    private var mp3buffer: ByteArray = ByteArray(0)
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private var amplitude = AtomicInteger(0)
    private var outputPath: String? = null
    private var androidLame: AndroidLame? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val minBufferSize = AudioRecord.getMinBufferSize(
        context.config.samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        context.config.microphoneMode,
        context.config.samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    )

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun prepare() {}

    override fun start() {
        val rawData = ShortArray(minBufferSize)
        mp3buffer = ByteArray((7200 + rawData.size * 2 * 1.25).toInt())

        outputStream = try {
            if (fileDescriptor != null) {
                FileOutputStream(fileDescriptor!!.fileDescriptor)
            } else {
                FileOutputStream(File(outputPath!!))
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return
        }

        androidLame = LameBuilder()
            .setInSampleRate(context.config.samplingRate)
            .setOutBitrate(context.config.bitrate / 1000)
            .setOutSampleRate(context.config.samplingRate)
            .setOutChannels(1)
            .build()

        ensureBackgroundThread {
            // Feeding the avatar engine below shares this thread with draining AudioRecord's
            // hardware buffer. That buffer is small (tuned for low latency, not tolerance) — at
            // default thread priority, the avatar engine's own CPU-heavy native rendering can
            // starve this loop of scheduling time between reads for long enough that the
            // hardware ring buffer overflows and read() starts returning repeated blocks (heard
            // as looping/stuck audio, both live and in the saved file). Urgent-audio priority is
            // the standard fix for exactly this class of capture starvation.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                audioRecord.startRecording()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return@ensureBackgroundThread
            }

            if (context.config.noiseCancellation && NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (e: Exception) {
                    Log.w("Mp3Recorder", "failed to create NoiseSuppressor, continuing without it", e)
                }
            }

            while (!isStopped.get()) {
                if (!isPaused.get()) {
                    val count = audioRecord.read(rawData, 0, minBufferSize)
                    if (count > 0) {
                        AvatarSessionHolder.feedAudioSegment(rawData.copyOf(count), context.config.samplingRate)
                        val encoded = androidLame!!.encode(rawData, rawData, count, mp3buffer)
                        if (encoded > 0) {
                            try {
                                updateAmplitude(rawData)
                                outputStream!!.write(mp3buffer, 0, encoded)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
        audioRecord.stop()
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        androidLame?.flush(mp3buffer)
        outputStream?.close()
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord.release()
    }

    override fun getMaxAmplitude(): Int {
        return amplitude.get()
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        this.fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    private fun updateAmplitude(data: ShortArray) {
        var sum = 0L
        for (i in 0 until minBufferSize step 2) {
            sum += abs(data[i].toInt())
        }
        amplitude.set((sum / (minBufferSize / 8)).toInt())
    }
}
