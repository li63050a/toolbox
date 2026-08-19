package com.toolbox.app

import android.app.Application
import com.toolbox.app.data.ConnectionRepository
import com.toolbox.app.log.CrashHandler
import com.toolbox.app.log.Log

object RepositoryProvider {
    lateinit var connections: ConnectionRepository
}

class ToolboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        Log.i("App", "工具箱启动")
        RepositoryProvider.connections = ConnectionRepository(this)
    }
}