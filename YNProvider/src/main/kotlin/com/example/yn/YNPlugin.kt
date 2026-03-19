package com.example.yn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YNPlugin : Plugin() {
    override fun load(context: Context) {
        YNMergeServer.ensureStarted()
        registerMainAPI(YNProvider())
    }
    override fun beforeUnload() {
        YNMergeServer.shutdown()
    }
}
