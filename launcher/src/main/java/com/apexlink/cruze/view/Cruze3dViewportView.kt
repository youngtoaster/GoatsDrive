package com.apexlink.cruze.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Hardware-accelerated 3D Viewport displaying the interactive 2012 Chevrolet Cruze LTZ
 * with 60fps WebGL rendering and bidirectional JNI / Kotlin communication.
 */
@SuppressLint("SetJavaScriptEnabled")
class Cruze3dViewportView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    interface Callback {
        fun onModelReady()
        fun onPartInteracted(part: String, isOpen: Boolean)
    }

    var callback: Callback? = null
    private var isModelReady = false

    init {
        setBackgroundColor(0)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("Cruze3D_JS", "${message?.message()} -- line ${message?.lineNumber()}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        addJavascriptInterface(object {
            @JavascriptInterface
            fun onModelLoaded() {
                post {
                    isModelReady = true
                    callback?.onModelReady()
                }
            }

            @JavascriptInterface
            fun onPartTapped(part: String, isOpen: Boolean) {
                post {
                    callback?.onPartInteracted(part, isOpen)
                }
            }
        }, "AndroidCar")

        loadUrl("file:///android_asset/cruze3d/index.html")
    }

    fun setDoor(door: String, isOpen: Boolean) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setDoorState('$door', $isOpen);")
    }

    fun setLights(headlightsOn: Boolean, brakeOn: Boolean, leftSignal: Boolean, rightSignal: Boolean) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setLightsState($headlightsOn, $brakeOn, $leftSignal, $rightSignal);")
    }

    fun setExteriorColor(hexColor: String) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setExteriorColor('$hexColor');")
    }

    fun setSteeringAngle(deg: Float) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setSteeringAngle($deg);")
    }

    fun setSpeed(mph: Int) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setSpeed($mph);")
    }

    fun setCameraPreset(preset: String) {
        evaluate("window.CruzeBridge && window.CruzeBridge.setCameraPreset('$preset');")
    }

    private fun evaluate(script: String) {
        post {
            evaluateJavascript(script, null)
        }
    }
}
