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
    fun initializeSdk(context: Context, appId: String, onInitialized: () -> Unit)
    fun loadInterstitial(
        context: Context,
        onLoaded: (AdInstanceHandle) -> Unit,
        onFailed: () -> Unit
    )
}

class StartIoInterstitialGateway : InterstitialAdGateway {
    override fun initializeSdk(context: Context, appId: String, onInitialized: () -> Unit) {
        StartAppSDK.initParams(context.applicationContext, appId)
            .setReturnAdsEnabled(false)
            .setCallback { onInitialized() }
            .init()
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

                            override fun adClicked(ad: Ad) {
                                // Click handled natively by SDK
                            }

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
    private var pendingContext: Context? = null

    fun initialize(context: Context) {
        if (isSdkInitialized || isSdkInitializing) return
        isSdkInitializing = true
        pendingContext = context.applicationContext
        runCatching {
            gateway.initializeSdk(context.applicationContext, START_IO_APP_ID) {
                mainPoster(Runnable {
                    isSdkInitialized = true
                    isSdkInitializing = false
                })
            }
        }.onFailure {
            isSdkInitializing = false
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
            initialize(context)
        }
        if (isAdLoading || isAdReady || isAdShowing) return

        isAdLoading = true
        val preloadToken = activeSessionToken
        runCatching {
            gateway.loadInterstitial(
                context = context,
                onLoaded = { handle ->
                    mainPoster(Runnable {
                        isAdLoading = false
                        // Keep preloaded ad ready even across episode transitions
                        preloadedAd = handle
                        isAdReady = true
                        val _unused = preloadToken
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
     * Triggered strictly on video playback ENDED event.
     * Never triggered on pause, back navigation, or manual episode switching.
     */
    fun onEpisodeEnded(
        context: Context,
        sessionToken: Long,
        hasNextEpisode: Boolean,
        onProceedNextEpisode: () -> Unit
    ) {
        if (!hasNextEpisode) {
            return
        }
        if (sessionToken != activeSessionToken) {
            return
        }
        if (isAdShowing || isAdProcessing) {
            return
        }

        isAdProcessing = true
        val adToShow = preloadedAd
        if (adToShow == null || !isAdReady) {
            isAdProcessing = false
            isAdShowing = false
            onProceedNextEpisode()
            return
        }

        // Consume preloaded ad reference before showing
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
                    onProceedNextEpisode()
                    preloadIfNeeded(context)
                }
            })
        }

        val shown = try {
            adToShow.show(
                onDisplayed = {
                    mainPoster(Runnable {
                        isAdShowing = true
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
}

object StartIoAdManager {
    val controller: InterstitialAdController by lazy { InterstitialAdController() }
}
