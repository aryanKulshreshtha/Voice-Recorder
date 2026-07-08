package org.fossify.voicerecorder

import org.fossify.commons.FossifyApp
import org.fossify.voicerecorder.helpers.AvatarSessionHolder

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        AvatarSessionHolder.initialize(this)
    }
}
