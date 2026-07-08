package org.fossify.voicerecorder.helpers

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity

/**
 * No-op stand-in for the foss flavor, which does not depend on AvatarSDK. Mirrors the public API
 * of the withAvatar flavors' implementation so common code never needs to know which is compiled
 * in.
 */
object AvatarSessionHolder {
    fun initialize(context: Context) = Unit

    fun startSession(activity: ComponentActivity, renderTarget: GLSurfaceView, recordingBaseName: String) = Unit

    fun feedAudioSegment(pcm: ShortArray, sampleRate: Int) = Unit

    fun completeSession() = Unit

    fun cancelSession() = Unit
}
