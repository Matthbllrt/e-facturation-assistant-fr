package com.glasscontrol.dyson

import android.app.Application
import com.glasscontrol.dyson.work.RefreshScheduler

class DysonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DysonServices.init(this)
        RefreshScheduler.ensureScheduled(this)
    }
}
