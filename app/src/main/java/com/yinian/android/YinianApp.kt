package com.yinian.android

import android.app.Application
import com.yinian.android.data.AppContainer
import com.yinian.android.data.DefaultAppContainer
import com.yinian.android.data.SeedInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YinianApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        SeedInitializer(container.database, appScope).runIfEmpty()
    }
}
