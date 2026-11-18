package com.localmind.app

import android.app.Application
import com.localmind.app.di.AppContainer

class LocalMindApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
