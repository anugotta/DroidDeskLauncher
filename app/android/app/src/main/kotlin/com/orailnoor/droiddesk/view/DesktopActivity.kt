package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import android.view.SurfaceHolder
import android.widget.FrameLayout
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.view.Gravity
import android.content.res.ColorStateList
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var connectionRequested = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var placeholder: FrameLayout
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private var inputModeButton: Button? = null

    companion object {
        private const val TAG = "DesktopActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        shouldStartSession = intent.getBooleanExtra("startSession", false)
        sessionMode = intent.getStringExtra("mode") ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: "xfce4"

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        setContentView(placeholder)

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — setting up LorieView")
            setupLorieView()
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        X11InputController.configureDisplayScale()
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView

        // Keep Android overlay controls above the X11 SurfaceView.
        lorieView!!.setZOrderOnTop(false)
        placeholder.setBackgroundColor(Color.BLACK)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        placeholder.addView(lorieView, params)
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        lorieView!!.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                synchronized(this@DesktopActivity) {
                    if (!connectionRequested && !LorieView.connected()) {
                        connectionRequested = true
                        connectToX11Service()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
            }
        })
    }

    private fun connectToX11Service() {
        if (LorieView.connected()) {
            lorieView?.requestFocus()
            return
        }

        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { connectionFd, logcatFd ->
                try {
                    LorieView.connect(connectionFd.detachFd())
                    logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                    Log.i(TAG, "LorieView connected to the :x11 service process")

                    inputController = X11InputController(lorieView!!)
                    addInputModeButton()
                    lorieView?.requestFocus()
                    startDesktopSessionIfRequested()
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = ::showX11Error,
        ).also { it.connect() }
    }

    private fun addInputModeButton() {
        if (inputModeButton != null) return
        val density = resources.displayMetrics.density
        inputModeButton = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.argb(220, 28, 38, 52))
            elevation = 6 * density
            text = inputController?.modeLabel() ?: "Trackpad"
            setOnClickListener {
                inputController?.nextMode()
                text = inputController?.modeLabel() ?: "Trackpad"
                Toast.makeText(this@DesktopActivity, "Input mode: $text", Toast.LENGTH_SHORT).show()
            }
        }.also { button ->
            val params = FrameLayout.LayoutParams(
                (116 * density).toInt(),
                (40 * density).toInt(),
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = (52 * density).toInt()
                marginEnd = (8 * density).toInt()
            }
            placeholder.addView(button, params)
            button.bringToFront()
        }
    }

    private fun startDesktopSessionIfRequested() {
        if (!shouldStartSession) return
        shouldStartSession = false
        Thread({
            Log.i(TAG, "Starting Linux desktop session after X server connection")
            try {
                if (sessionMode == "chroot") {
                    chrootRuntime.startSession(desktopEnv)
                } else {
                    linuxRuntime.startSession(desktopEnv, "x11")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop session failed", error)
            }
        }, "LinuxDesktopSession").start()
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        Toast.makeText(this, "X11 Error: $message", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        inputController?.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        super.onDestroy()
    }
}
