package org.fossify.voicerecorder.helpers

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import com.avatarsdk.api.AvatarConfig
import com.avatarsdk.api.AvatarError
import com.avatarsdk.api.AvatarListener
import com.avatarsdk.api.AvatarResult
import com.avatarsdk.api.AvatarSDK
import com.avatarsdk.api.AvatarStreamingSession

/**
 * Bridges Voice-Recorder's MP3 recording path to AvatarSDK's live-streaming avatar overlay.
 *
 * Exactly one [AvatarStreamingSession] is ever active at a time, and every session is guaranteed
 * to reach a terminal [completeSession]/[cancelSession] call. AvatarSDK's underlying engine is a
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

    fun initialize(context: Context) {
        if (AvatarSDK.isInitialized) {
            return
        }

        AvatarSDK.initialize(context, AvatarConfig.Builder().build())
    }

    @Synchronized
    fun startSession(activity: ComponentActivity) {
        if (session != null) {
            return
        }

        isReady = false
        try {
            session = AvatarSDK.startStreamingSession(
                activity = activity,
                listener = object : AvatarListener {
                    override fun onSuccess(result: AvatarResult) = clearSession()

                    override fun onFailure(error: AvatarError) {
                        Log.w(TAG, "avatar session failed: ${error.message}")
                        clearSession()
                    }

                    override fun onEngineReady() {
                        isReady = true
                    }
                },
            )
        } catch (e: RuntimeException) {
            // The SDK's overlay window can fail to attach (e.g. a stale/invalid Activity window
            // token). Never let that reach the caller — the actual recording must be unaffected.
            Log.w(TAG, "failed to start avatar session, continuing without it", e)
            clearSession()
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
    }
}
