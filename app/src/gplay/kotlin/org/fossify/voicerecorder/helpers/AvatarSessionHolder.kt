package org.fossify.voicerecorder.helpers

import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.activity.ComponentActivity
import com.avatarsdk.api.AvatarConfig
import com.avatarsdk.api.AvatarError
import com.avatarsdk.api.AvatarListener
import com.avatarsdk.api.AvatarResult
import com.avatarsdk.api.AvatarSDK
import com.avatarsdk.api.AvatarStreamingSession
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.createDocumentFile
import java.io.File

/**
 * Bridges Voice-Recorder's MP3 recording path to AvatarSDK's live-streaming avatar rendering.
 *
 * Renders directly into a host-owned [GLSurfaceView] (see [startSession]) rather than the SDK's
 * own floating overlay, so the avatar appears embedded in the recorder screen. Exactly one
 * [AvatarStreamingSession] is ever active at a time, and every session is guaranteed to reach a
 * terminal [completeSession]/[cancelSession] call. AvatarSDK's underlying engine is a
 * process-wide singleton that only becomes idle again once a session's [AvatarListener] callback
 * fires — starting a second session while one is still active, or leaking a session without a
 * terminal call, is what wedges the engine for every subsequent recording.
 */
object AvatarSessionHolder {
    private const val TAG = "AvatarSessionHolder"

    @Volatile
    private var session: AvatarStreamingSession? = null

    @Volatile
    private var isReady = false

    @Volatile
    private var activeRenderTarget: GLSurfaceView? = null

    fun initialize(context: Context) {
        if (AvatarSDK.isInitialized) {
            return
        }

        AvatarSDK.initialize(context, AvatarConfig.Builder().build())
    }

    /**
     * Starts a session rendering directly into [renderTarget] (e.g. a [GLSurfaceView] embedded
     * in the recorder screen's own layout) instead of the SDK's own overlay window. The caller
     * owns [renderTarget]'s visibility — this only forwards the GL lifecycle
     * ([GLSurfaceView.onResume]/[GLSurfaceView.onPause]) that the SDK's docs require the host to
     * manage, tied to this session's own start/end rather than the Activity's lifecycle, which
     * is sufficient since a session's lifetime always sits inside the recording it's for.
     *
     * [recordingBaseName] is the filename (no extension) of the MP3 recording this session
     * renders for — on success, the rendered video is saved alongside it in the Recordings
     * folder as "$recordingBaseName.mp4".
     */
    @Synchronized
    fun startSession(activity: ComponentActivity, renderTarget: GLSurfaceView, recordingBaseName: String) {
        if (session != null) {
            return
        }

        isReady = false
        try {
            session = AvatarSDK.startStreamingSession(
                activity = activity,
                listener = object : AvatarListener {
                    override fun onSuccess(result: AvatarResult) {
                        if (result.videoUri != Uri.EMPTY) {
                            saveVideoToRecordings(activity, result.videoUri, recordingBaseName)
                        }
                        clearSession()
                    }

                    override fun onFailure(error: AvatarError) {
                        Log.w(TAG, "avatar session failed: ${error.message}")
                        clearSession()
                    }

                    override fun onEngineReady() {
                        isReady = true
                    }
                },
                renderTarget = renderTarget,
                // This is a direct-audio (feedAudioSegment) session, not TTS — the SDK's
                // generateVideo flag defaults to off since most integrations don't need a saved
                // file, but a companion video is the whole point of this feature here.
                generateVideo = true,
            )
            // Only safe to call once the SDK has installed its renderer on renderTarget above
            // (GLSurfaceView.onResume() dereferences its GL thread, which doesn't exist yet
            // otherwise — calling this first throws a NullPointerException).
            renderTarget.onResume()
            activeRenderTarget = renderTarget
        } catch (e: RuntimeException) {
            // Attaching the render target or starting the session can fail (e.g. a stale/invalid
            // Activity window token). Never let that reach the caller — the actual recording
            // must be unaffected.
            Log.w(TAG, "failed to start avatar session, continuing without it", e)
            clearSession()
        }
    }

    /**
     * Copies AvatarSDK's app-private rendered video into the public Recordings folder next to
     * its MP3, using the same SAF-aware write path the app's own recordings are saved through
     * (see [org.fossify.voicerecorder.extensions.createDocumentFile]). A failure here must never
     * surface as a recording error — the MP3 itself already saved successfully independently of
     * this companion video.
     */
    private fun saveVideoToRecordings(context: Context, videoUri: Uri, recordingBaseName: String) {
        val sourcePath = videoUri.path ?: return
        val sourceFile = File(sourcePath)
        try {
            if (isRPlus()) {
                val destPath = "${context.config.saveRecordingsFolder}/$recordingBaseName.mp4"
                val destUri = context.createDocumentFile(destPath) ?: return
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }
            } else {
                val destFile = File(context.config.saveRecordingsFolder, "$recordingBaseName.mp4")
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { input.copyTo(it) }
                }
            }
            sourceFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "failed to save avatar video to recordings, discarding it", e)
        }
    }

    fun feedAudioSegment(pcm: ShortArray, sampleRate: Int) {
        if (!isReady) {
            return
        }

        try {
            session?.feedAudioSegment(pcm, sampleRate)
        } catch (e: RuntimeException) {
            Log.w(TAG, "failed to feed audio segment, dropping it", e)
        }
    }

    @Synchronized
    fun completeSession() {
        try {
            session?.complete()
        } catch (e: RuntimeException) {
            Log.w(TAG, "failed to complete avatar session", e)
        }
        clearSession()
    }

    @Synchronized
    fun cancelSession() {
        try {
            session?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "failed to cancel avatar session", e)
        }
        clearSession()
    }

    private fun clearSession() {
        session = null
        isReady = false
        try {
            activeRenderTarget?.onPause()
        } catch (e: RuntimeException) {
            Log.w(TAG, "failed to pause avatar render target", e)
        }
        activeRenderTarget = null
    }
}
