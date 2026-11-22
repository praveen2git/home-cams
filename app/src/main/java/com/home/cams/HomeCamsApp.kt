package com.home.cams

import android.app.Application
import com.home.cams.onvif.OnvifClient
import com.home.cams.sources.ConfigStore
import com.home.cams.update.Updater
import okhttp3.OkHttpClient

/** Holds the few app-wide objects. Manual wiring, no DI library. */
class HomeCamsApp : Application() {
    val store by lazy { ConfigStore(getSharedPreferences("config", MODE_PRIVATE)) }
    private val http by lazy { OkHttpClient() }
    val onvif by lazy { OnvifClient(http) }
    val updater by lazy { Updater(this, http, BuildConfig.VERSION_NAME) }
}
