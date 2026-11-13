package com.example.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener

interface AdInstanceHandle {
    fun show(
        onDisplayed: () -> Unit,
        onHidden: () -> Unit,
        onNotDisplayed: () -> Unit
    ): Boolean
}

interface InterstitialAdGateway {
    fun initializeSdk(
        context: Context,
        appId: String,
        onInitialized: () -> Unit
    )

    fun loadInterstitial(
        context: Context,
        onLoaded: (AdInstanceHandle) -> Unit,
        onFailed: () -> Unit
    )
}

class StartIoInterstitialGateway : InterstitialAdGateway {

    override fun initializeSdk(
        context: Context,
        appId: String,
        onInitialized: () -> Unit
    ) {
        StartAppSDK.initParams(context.applicationContext, appId)
            .setReturnAdsEnabled(false)
            .setCallback {
                onInitialized()
            }
            .init()

        StartAppSDK.setTestAdsEnabled(true)
        StartAppAd.disableAutoInterstitial()
    }

    override fun loadInterstitial(
        context: Context,
        onLoaded: (AdInstanceHandle) -> Unit,
        onFailed: () -> Unit
    ) {
        val hostContext = unwrapActivity(context) ?: context.applicationContext
        val startAppAd = StartAppAd(hostContext)

        startAppAd.loadAd(object : AdEventListener {

            override fun onReceiveAd(ad: Ad) {
                val handle = object : AdInstanceHandle {

                    override fun show(
                        onDisplayed: () -> Unit,
                        onHidden: () -> Unit,
                        onNotDisplayed: () -> Unit
                    ): Boolean {
                        return startAppAd.showAd(object : AdDisplayListener {

                            override fun adHidden(ad: Ad) {
                                onHidden()
                            }

                            override fun adDisplayed(ad: Ad) {
                                onDisplayed()
                            }

                            override fun adClicked(ad: Ad) = Unit

                            override fun adNotDisplayed(ad: Ad) {
                                onNotDisplayed()
                            }
                        })
                    }
                }

                onLoaded(handle)
            }

            override fun onFailedToReceiveAd(ad: Ad?) {
                onFailed()
            }
        })
    }

    private fun unwrapActivity(context: Context): Activity? {
        var current: Context? = context

        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }

        return null
    }
}

class InterstitialAdController(
    private val gateway: InterstitialAdGateway = StartIoInterstitialGateway(),
    private val mainPoster: (Runnable) -> Unit = { runnable ->
        val mainLooper = Looper.getMainLooper()

        if (Looper.myLooper() == mainLooper) {
            runnable.run()
        } else {
            Handler(mainLooper).post(runnable)
        }
    }
) {

    companion object {
        const val START_IO_APP_ID = "210355804"
    }

    @Volatile
    var isSdkInitialized: Boolean = false
        private set

    @Volatile
    private var isSdkInitializing: Boolean = false

    @Volatile
    var isAdLoading: Boolean = false
        private set

    @Volatile
    var isAdReady: Boolean = false
        private set

    @Volatile
    var isAdShowing: Boolean = false
        private set

    @Volatile
    var isAdProcessing: Boolean = false
        private set

    private var preloadedAd: AdInstanceHandle? = null
    private var activeSessionToken: Long = 0L
    private var pendingPreloadContext: Context? = null

    fun initialize(context: Context) {
        if (isSdkInitialized || isSdkInitializing) return

        isSdkInitializing = true

        runCatching {
            gateway.initializeSdk(
                context.applicationContext,
                START_IO_APP_ID
            ) {
                mainPoster(Runnable {
                    isSdkInitialized = true
                    isSdkInitializing = false

                    val waitingContext = pendingPreloadContext
                    pendingPreloadContext = null

                    if (waitingContext != null) {
                        preloadIfNeeded(waitingContext)
                    }
                })
            }
        }.onFailure {
            isSdkInitializing = false
            pendingPreloadContext = null
        }
    }

    fun startNewSession(): Long {
        activeSessionToken += 1L
        isAdProcessing = false
        isAdShowing = false
        return activeSessionToken
    }

    fun cancelPendingTransition() {
        activeSessionToken += 1L
        isAdProcessing = false
        isAdShowing = false
    }

    fun preloadIfNeeded(context: Context) {
        if (!isSdkInitialized) {
            pendingPreloadContext = context.applicationContext
            initialize(context)
            return
        }

        if (isAdLoading || isAdReady || isAdShowing) return

        isAdLoading = true

        runCatching {
            gateway.loadInterstitial(
                context = context,
                onLoaded = { handle ->
                    mainPoster(Runnable {
                        isAdLoading = false
                        preloadedAd = handle
                        isAdReady = true
                    })
                },
                onFailed = {
                    mainPoster(Runnable {
                        isAdLoading = false
                        preloadedAd = null
                        isAdReady = false
                    })
                }
            )
        }.onFailure {
            isAdLoading = false
            preloadedAd = null
            isAdReady = false
        }
    }

    /**
     * Jalur bersama untuk semua perpindahan episode.
     * Jika iklan tidak tersedia, perpindahan tetap dilanjutkan.
     */
    fun requestEpisodeTransition(
        context: Context,
        sessionToken: Long,
        onProceed: () -> Unit
    ) {
        if (sessionToken != activeSessionToken) return
        if (isAdShowing || isAdProcessing) return

        isAdProcessing = true

        val adToShow = preloadedAd

        if (adToShow == null || !isAdReady) {
            mainPoster(Runnable {
                if (sessionToken != activeSessionToken) {
                    isAdProcessing = false
                    return@Runnable
                }

                isAdProcessing = false
                isAdShowing = false
                onProceed()
            })
            return
        }

        preloadedAd = null
        isAdReady = false
        isAdShowing = true

        var completed = false

        fun finishTransition() {
            mainPoster(Runnable {
                if (completed) return@Runnable
                completed = true

                isAdShowing = false
                isAdProcessing = false

                if (sessionToken == activeSessionToken) {
                    onProceed()
                    preloadIfNeeded(context)
                }
            })
        }

        val shown = try {
            adToShow.show(
                onDisplayed = {
                    mainPoster(Runnable {
                        if (sessionToken == activeSessionToken) {
                            isAdShowing = true
                        }
                    })
                },
                onHidden = {
                    finishTransition()
                },
                onNotDisplayed = {
                    finishTransition()
                }
            )
        } catch (_: Exception) {
            false
        }

        if (!shown) {
            finishTransition()
        }
    }

    /**
     * Kompatibilitas untuk pemanggilan auto-next yang lama.
     */
    fun onEpisodeEnded(
        context: Context,
        sessionToken: Long,
        hasNextEpisode: Boolean,
        onProceedNextEpisode: () -> Unit
    ) {
        if (!hasNextEpisode) return

        requestEpisodeTransition(
            context = context,
            sessionToken = sessionToken,
            onProceed = onProceedNextEpisode
        )
    }
}

object StartIoAdManager {
    val controller: InterstitialAdController by lazy {
        InterstitialAdController()
    }
}
