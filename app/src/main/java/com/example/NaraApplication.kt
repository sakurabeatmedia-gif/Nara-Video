
package com.example

import android.app.Application
import com.startapp.sdk.adsbase.StartAppSDK

class NaraApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        StartAppSDK.initParams(
            applicationContext,
            "210355804"
        )
            .setReturnAdsEnabled(false)
            .setCallback {
                android.util.Log.d(
                    "STARTIO",
                    "Start.io SDK berhasil diinisialisasi"
                )
            }
            .init()

        // Aktifkan hanya untuk pengujian.
        StartAppSDK.setTestAdsEnabled(true)
    }
}
