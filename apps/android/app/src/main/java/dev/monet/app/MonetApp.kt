package dev.monet.app

import android.app.Application
import dev.monet.app.core.di.AppContainer

class MonetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
