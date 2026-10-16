package com.azeem.azremoteconnect

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.content.pm.ActivityInfo
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.AudioFormat
import android.content.res.Configuration
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import android.graphics.drawable.ColorDrawable
import java.io.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var fingerprintInput: EditText
    private lateinit var status: TextView
    private lateinit var screen: ImageView
    private lateinit var connectButton: Button
    private lateinit var keyboardInput: EditText
    private lateinit var settingsPanel: LinearLayout
    private lateinit var hostDetails: TextView
    private val projectionRequest = 7312
    private var remoteTouchConsentForNextSession = false
    private lateinit var fullscreenButton: Button
    private lateinit var audioButton: Button
    @Volatile private var audioEnabled = true
    private var audioPlayer: AudioTrack? = null
    private var audioRate = 0
    private lateinit var rootLayout: LinearLayout
    private lateinit var modeBar: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var remotePage: FrameLayout
    private lateinit var dashboardPage: LinearLayout
    private lateinit var sessionStatus: TextView
    private lateinit var sessionTopBar: LinearLayout
    private lateinit var sessionBottomBar: LinearLayout
    private lateinit var deviceStatus: TextView
    private lateinit var touchpadButton: Button
    private lateinit var screenTouchButton: Button
    private var remotePageVisible = false

    private var touchpadMode = false
    private var fullScreen = false
    private var previousX = 0f
    private var previousY = 0f
    private var moved = false
    private var downTime = 0L
    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: DataOutputStream? = null
    private val writeLock = Any()
    private var lastX = 0.5f
    private var lastY = 0.5f
    private var imageW = 0
    private var imageH = 0

    // Blue dashboard and remote-session page share one Activity so the TLS session survives
    // a page transition and immersive orientation changes without reconnecting.
    private fun dp(size: Int) = (size * resources.displayMetrics.density + 0.5f).toInt()
    private val navy get() = Color.rgb(5, 22, 49)
    private val blue get() = Color.rgb(21, 127, 244)
    private val panelBlue get() = Color.rgb(10, 42, 84)
    private val ice get() = Color.rgb(227, 243, 255)

    private fun rounded(color: Int, stroke: Boolean = true, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke) setStroke(dp(1), Color.rgb(38, 116, 194))
        }

    private fun textLine(value: String, size: Float = 14f, secondary: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(if (secondary) Color.rgb(160, 198, 237) else ice)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun blueButton(value: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(if (primary) blue else panelBlue)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { action() }
        }

    private fun rowButton(parent: LinearLayout, button: Button, weight: Float = 1f) {
        val lp = LinearLayout.LayoutParams(0, dp(49), weight)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        parent.addView(button, lp)
    }

    private fun field(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
        textSize = 14f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(159, 186, 217))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(Color.rgb(3, 26, 61), radius = 12)
    }

    private fun addSpace(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun infoDialog(title: String, message: String) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(4, 20, 45)
        window.navigationBarColor = Color.rgb(3, 16, 39)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
        }
        dashboardPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(8), dp(13), dp(8))
            setBackgroundColor(navy)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(textLine("◉  AZ Remote Connect", 23f).apply { setTypeface(null, android.graphics.Typeface.BOLD) })
        brand.addView(textLine("Control your PC, from anywhere on your trusted network", 11f, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(blueButton("⚙") {
            infoDialog("Settings and privacy", "Remote sessions require a pairing code and host approval. The six-character verification prefix is not suitable for untrusted networks. Use a trusted VPN for remote connections. Screen-sharing and remote-touch consent remain separate.")
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        dashboardPage.addView(header)
        addSpace(dashboardPage, 15)

        // Device card, pairing fields and connection state.
        val deviceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(9, 47, 104))
        }
        val deviceHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        deviceHead.addView(textLine("▣", 32f), LinearLayout.LayoutParams(dp(48), dp(50)))
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(textLine("Connect to a device", 18f).apply { setTypeface(null, android.graphics.Typeface.BOLD) })
        deviceStatus = textLine("●  Not connected", 12f, true)
        identity.addView(deviceStatus)
        deviceHead.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        deviceCard.addView(deviceHead)
        addSpace(deviceCard, 7)
        hostInput = field("Laptop or phone IP address", "192.168.1.2")
        codeInput = field("6-digit pairing code")
        fingerprintInput = field("6-character verification code")
        for (input in listOf(hostInput, codeInput, fingerprintInput)) {
            val lp = LinearLayout.LayoutParams(-1, dp(48))
            lp.setMargins(0, dp(4), 0, dp(4))
            deviceCard.addView(input, lp)
        }
        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = blueButton("Connect", true) { connect() }
        rowButton(connectRow, connectButton)
        rowButton(connectRow, blueButton("Disconnect") { disconnect() })
        deviceCard.addView(connectRow)
        status = textLine("Ready. Connect using the same hotspot, Wi-Fi, or trusted VPN.", 12f, true)
        deviceCard.addView(status)
        dashboardPage.addView(deviceCard)
        addSpace(dashboardPage, 12)

        // Modes now select how the pointer behaves on the dedicated remote screen.
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        touchpadButton = blueButton("☝  Touchpad", true) { touchpadMode = true; updateModeButtons(); if (running) showRemotePage() }
        screenTouchButton = blueButton("▣  Screen Touch") { touchpadMode = false; updateModeButtons(); if (running) showRemotePage() }
        rowButton(modeRow, touchpadButton)
        rowButton(modeRow, screenTouchButton)
        rowButton(modeRow, blueButton("♫  Media") { if (running) showRemotePage() else infoDialog("Media", "Connect to a device first.") })
        dashboardPage.addView(modeRow)
        addSpace(dashboardPage, 7)

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(10))
            background = rounded(Color.rgb(7, 39, 80))
        }
        previewCard.addView(textLine("LIVE SCREEN PREVIEW", 12f, true))
        val previewFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(1, 12, 29), radius = 12)
            clipToOutline = true
        }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewFrame.addView(previewImage, FrameLayout.LayoutParams(-1, -1))
        val overlay = textLine("Tap to open the remote screen", 12f).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(175, 0, 17, 44))
        }
        previewFrame.addView(overlay, FrameLayout.LayoutParams(-1, dp(33), Gravity.BOTTOM))
        previewFrame.setOnClickListener {
            if (running) showRemotePage()
            else infoDialog("Not connected", "Enter the host IP, pairing code, and verification code, then tap Connect.")
        }
        val previewLp = LinearLayout.LayoutParams(-1, dp(160))
        previewLp.topMargin = dp(9)
        previewCard.addView(previewFrame, previewLp)
        previewCard.addView(blueButton("↗  Open full remote screen", true) {
            if (running) showRemotePage()
            else infoDialog("Not connected", "Connect to a laptop or phone first.")
        }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(9) })
        dashboardPage.addView(previewCard)
        addSpace(dashboardPage, 10)

        val controls1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(controls1, blueButton("▣  Keyboard") { if (running) { showRemotePage(); openRemoteKeyboard() } else infoDialog("Keyboard", "Connect to a device first.") })
        rowButton(controls1, blueButton("⛶  Full Screen") { if (running) { showRemotePage(); setFullscreen(true) } else infoDialog("Full Screen", "Connect to a device first.") })
        dashboardPage.addView(controls1)
        val controls2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(controls2, blueButton("◖))  Volume") { audioEnabled = !audioEnabled; if (!audioEnabled) { audioPlayer?.pause(); audioPlayer?.flush() } else audioPlayer?.play(); updateAudioButton() })
        rowButton(controls2, blueButton("✕  Disconnect") { disconnect() })
        dashboardPage.addView(controls2)
        audioButton = blueButton("PC Audio: On") {
            audioEnabled = !audioEnabled
            if (!audioEnabled) { audioPlayer?.pause(); audioPlayer?.flush() } else audioPlayer?.play()
            updateAudioButton()
        }
        dashboardPage.addView(audioButton, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(7) })
        addSpace(dashboardPage, 12)

        val hostCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panelBlue)
        }
        hostCard.addView(textLine("SHARE THIS ANDROID PHONE", 12f, true))
        val hosting = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(hosting, blueButton("Share phone", true) { showScreenShareDisclosure() })
        rowButton(hosting, blueButton("Stop sharing") {
            startService(Intent(this@MainActivity, AndroidHostService::class.java).setAction(AndroidHostService.ACTION_STOP))
        })
        hostCard.addView(hosting)
        if (BuildConfig.FLAVOR == "full") {
            hostCard.addView(blueButton("Optional remote touch permission") { showRemoteTouchDisclosure() },
                LinearLayout.LayoutParams(-1, dp(44)))
        }
        hostDetails = textLine("Screen sharing is off; Accessibility is not required for viewing.", 12f, true)
        hostCard.addView(hostDetails)
        hostCard.addView(blueButton("Refresh phone host code") {
            hostDetails.text = if (AndroidHostService.active)
                "Phone hosting  •  Pair: " + AndroidHostService.code + "  •  Verify: " + AndroidHostService.verification
            else "Phone host is off. " + AndroidHostService.hostError
        }, LinearLayout.LayoutParams(-1, dp(45)))
        dashboardPage.addView(hostCard)

        val scroll = ScrollView(this).apply {
            fillViewport = false
            isVerticalScrollBarEnabled = false
            addView(dashboardPage, ScrollView.LayoutParams(-1, -2))
        }
        val homeShell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeShell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(Color.rgb(5, 32, 70), false)
        }
        rowButton(nav, blueButton("⌂  Home", true) { showDashboard() })
        rowButton(nav, blueButton("⛓  Remote") { if (running) showRemotePage() else infoDialog("Remote", "Connect to a device first.") })
        rowButton(nav, blueButton("▱  Files") { infoDialog("Files", "File transfer is not implemented in this preview.") })
        rowButton(nav, blueButton("⚙  Settings") { infoDialog("Settings", "Use only trusted networks. Approve each incoming remote session at the host.") })
        homeShell.addView(nav)
        rootLayout.addView(homeShell, LinearLayout.LayoutParams(-1, -1))

        // Dedicated AnyDesk-style viewer page; the dashboard preview never acts as a touchpad.
        remotePage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        screen = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        remotePage.addView(screen, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.argb(238, 4, 30, 69))
        }
        rowButton(top, blueButton("‹  Home") { showDashboard() }, 0.7f)
        sessionStatus = textLine("Remote session", 13f)
        top.addView(sessionStatus, LinearLayout.LayoutParams(0, -1, 1.4f))
        fullscreenButton = blueButton("⛶  Full") { setFullscreen(!fullScreen) }
        rowButton(top, fullscreenButton, 0.8f)
        sessionTopBar = top
        remotePage.addView(top, FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(7))
            setBackgroundColor(Color.argb(231, 3, 25, 56))
        }
        val controlBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(controlBar, blueButton("Touchpad") { touchpadMode = true; updateModeButtons() })
        rowButton(controlBar, blueButton("Screen touch") { touchpadMode = false; updateModeButtons() })
        rowButton(controlBar, blueButton("Right click") {
            thread { send(JSONObject().put("type","mouse").put("x",lastX.toDouble()).put("y",lastY.toDouble()).put("action","right")) }
        })
        bottom.addView(controlBar)
        val keyBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyboardInput = field("Type on laptop / phone")
        keyboardInput.visibility = View.GONE
        keyBar.addView(keyboardInput, LinearLayout.LayoutParams(0, dp(48), 2.2f))
        rowButton(keyBar, blueButton("Send") {
            val message = keyboardInput.text.toString()
            if (message.isNotEmpty()) { thread { send(JSONObject().put("type","text").put("value",message.take(200))) }; keyboardInput.text.clear() }
        }, 0.8f)
        rowButton(keyBar, blueButton("⌫") { thread { send(JSONObject().put("type","key").put("key","BACKSPACE")) } }, 0.55f)
        bottom.addView(keyBar)
        val mediaBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(mediaBar, blueButton("⌨ Keyboard") { openRemoteKeyboard() })
        rowButton(mediaBar, blueButton("↑ Scroll") { thread { send(JSONObject().put("type","scroll").put("delta",120)) } })
        rowButton(mediaBar, blueButton("↓ Scroll") { thread { send(JSONObject().put("type","scroll").put("delta",-120)) } })
        rowButton(mediaBar, blueButton("✕ Stop") { disconnect() })
        bottom.addView(mediaBar)
        sessionBottomBar = bottom
        remotePage.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        rootLayout.addView(remotePage, LinearLayout.LayoutParams(-1, -1))
        setContentView(rootLayout)
        updateModeButtons()
        updateAudioButton()
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                if (fullScreen) rootLayout.setPadding(0, 0, 0, 0)
                else rootLayout.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            window.decorView.requestApplyInsets()
        }
    }

    private fun updateModeButtons() {
        if (!::touchpadButton.isInitialized) return
        touchpadButton.background = rounded(if (touchpadMode) blue else panelBlue)
        screenTouchButton.background = rounded(if (touchpadMode) panelBlue else blue)
    }
    private fun updateAudioButton() {
        if (::audioButton.isInitialized) audioButton.text = if (audioEnabled) "◖))  PC Audio: On" else "◖))  PC Audio: Off"
    }
    private fun showRemotePage() {
        if (!running) return
        remotePageVisible = true
        (rootLayout.getChildAt(0)).visibility = View.GONE
        remotePage.visibility = View.VISIBLE
        sessionStatus.text = "●  " + hostInput.text.toString().trim() + "  •  " +
            (if (touchpadMode) "Touchpad" else "Screen touch")
    }
    private fun showDashboard() {
        if (fullScreen) setFullscreen(false)
        remotePageVisible = false
        (rootLayout.getChildAt(0)).visibility = View.VISIBLE
        remotePage.visibility = View.GONE
    }
    private fun openRemoteKeyboard() {
        keyboardInput.visibility = View.VISIBLE
        keyboardInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(keyboardInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showScreenShareDisclosure() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Share your phone screen?")
            .setMessage(
                "AZ RemoteConnect will capture what is visible on this phone, including " +
                "apps, messages and potentially sensitive information. Your screen will be " +
                "sent live over the encrypted connection to a device you explicitly approve " +
                "using the pairing code. The other person may record what they see. " +
                "Screen sharing is optional and can be stopped from the notification or app. " +
                "It does not require Accessibility access."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 811)
                    Toast.makeText(this, "Allow notifications for pairing approval, then tap Share again.",
                        Toast.LENGTH_LONG).show()
                } else {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
                }
            }.show()
    }

    private fun showRemoteTouchDisclosure() {
        if (BuildConfig.FLAVOR != "full") return
        android.app.AlertDialog.Builder(this)
            .setTitle("Optional Accessibility access for remote touch")
            .setMessage(
                "To let an approved remote device tap or swipe on this Android phone, " +
                "AZ RemoteConnect uses an Android Accessibility service. " +
                "Android may allow this service to observe interface content. This app's " +
                "service uses the API to dispatch touch gestures; it does not read or upload " +
                "text from Accessibility events. The approved remote controller can interact " +
                "with your phone and may see private content through screen sharing. " +
                "Enable this only for people you trust. You can leave it off and share your " +
                "screen in view-only mode, stop sharing, or disable the service in Android Settings."
            )
            .setNegativeButton("No, view only") { _, _ ->
                remoteTouchConsentForNextSession = false
            }
            .setPositiveButton("I agree: enable touch") { _, _ ->
                remoteTouchConsentForNextSession = true
                if (RemoteAccessibility.instance == null) {
                    Toast.makeText(this, "Enable AZ RemoteConnect in Accessibility settings, then return and tap Share.",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    Toast.makeText(this, "Touch enabled for your next approved sharing session.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    @Deprecated("Uses Activity result for Android MediaProjection authorization")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, AndroidHostService::class.java)
                .setAction(AndroidHostService.ACTION_START)
                .putExtra(AndroidHostService.EXTRA_RESULT, resultCode)
                .putExtra(AndroidHostService.EXTRA_DATA, data)
                .putExtra(AndroidHostService.EXTRA_ALLOW_REMOTE_TOUCH,
                    BuildConfig.FLAVOR == "full" && remoteTouchConsentForNextSession && RemoteAccessibility.instance != null)
            startForegroundService(intent)
            remoteTouchConsentForNextSession = false
            hostDetails.text = "Hosting enabled. Confirm pairing and verification code in the host notification."
        } else if (requestCode == projectionRequest) {
            remoteTouchConsentForNextSession = false
        }
    }
    override fun onResume() {
        super.onResume()
        if (::hostDetails.isInitialized && AndroidHostService.active) {
            hostDetails.text = "Phone host • Pair: ${AndroidHostService.code} • Verify: ${AndroidHostService.verification} • Port 48765"
        }
    }

    private fun info(message: String) = runOnUiThread {
        status.text = message
        if (::deviceStatus.isInitialized) {
            deviceStatus.text = when {
                message.startsWith("Connected") -> "●  Connected • " + hostInput.text.toString().trim()
                message.startsWith("Connecting") -> "●  Connecting…"
                else -> "●  Disconnected"
            }
        }
    }

    private fun connect() {
        if (running) return
        val ip = hostInput.text.toString().trim()
        val code = codeInput.text.toString().trim()
        val pin = fingerprintInput.text.toString().replace(":", "").replace(" ", "").uppercase()
        if (!code.matches(Regex("[0-9]{6}")) || !pin.matches(Regex("[0-9A-F]{6}"))) {
            info("Enter the 6-digit code and the 6-character verification code shown on the laptop.")
            return
        }
        running = true
        connectButton.isEnabled = false
        info("Connecting to $ip:48765 ...")
        thread(name = "remote-viewer") {
            try {
                val trustManager = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing certificate")
                    }
                }
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                val raw = java.net.Socket()
                raw.connect(InetSocketAddress(ip, 48765), 8000)
                val ssl = context.socketFactory.createSocket(raw, ip, 48765, true) as SSLSocket
                socket = ssl
                ssl.soTimeout = 20000
                ssl.startHandshake()
                val certificate = ssl.session.peerCertificates.first() as X509Certificate
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02X".format(it) }
                if (actual.take(6) != pin) throw SecurityException("Fingerprint mismatch. Connection rejected.")
                val input = DataInputStream(BufferedInputStream(ssl.inputStream))
                output = DataOutputStream(BufferedOutputStream(ssl.outputStream))
                send(JSONObject().put("type", "pair").put("code", code))
                val approval = read(input)
                if (approval.optString("type") != "approved") throw IOException("Host rejected or closed the pairing request")
                ssl.soTimeout = 0
                info("Connected. Remote screen active.")
                runOnUiThread { showRemotePage() }
                while (running) {
                    val message = read(input)
                    if (message.optString("type") == "audio") {
                        if (audioEnabled) {
                            val sampleRate = message.optInt("sampleRate", 48000)
                            if (sampleRate in 8000..192000) {
                                val pcm = android.util.Base64.decode(message.getString("pcm"), android.util.Base64.DEFAULT)
                                try { playAudio(pcm, sampleRate) }
                                catch (ex: Exception) { info("Audio unavailable: ${ex.message}") }
                            }
                        }
                    } else if (message.optString("type") == "frame") {
                        val bytes = android.util.Base64.decode(message.getString("jpeg"), android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                        runOnUiThread {
                            if (running) {
                                imageW = bitmap.width; imageH = bitmap.height
                                screen.setImageBitmap(bitmap)
                                previewImage.setImageBitmap(bitmap)
                            } else bitmap.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                info("Disconnected: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
                try { socket?.close() } catch (_: Exception) {}
                output = null; socket = null
                releaseAudio()
                runOnUiThread {
                    connectButton.isEnabled = true
                    if (::remotePage.isInitialized) showDashboard()
                }
            }
        }
    }

    private fun read(input: DataInputStream): JSONObject {
        val length = input.readInt()
        if (length <= 0 || length > 8 * 1024 * 1024) throw IOException("Invalid frame size")
        val payload = ByteArray(length)
        input.readFully(payload)
        return JSONObject(String(payload, Charsets.UTF_8))
    }

    private fun send(msg: JSONObject) {
        synchronized(writeLock) {
            val stream = output ?: return
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 4096) return
            try { stream.writeInt(bytes.size); stream.write(bytes); stream.flush() }
            catch (_: IOException) { disconnect() }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!running || imageW == 0 || imageH == 0) return false
        // ImageView.FIT_CENTER letterboxes to preserve the host desktop aspect ratio.
        val scale = min(screen.width.toFloat() / imageW, screen.height.toFloat() / imageH)
        val shownW = imageW * scale
        val shownH = imageH * scale
        val x = (event.x - (screen.width - shownW) / 2f) / shownW
        val y = (event.y - (screen.height - shownH) / 2f) / shownH
        if (x !in 0f..1f || y !in 0f..1f) return true
        if (touchpadMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { previousX = event.x; previousY = event.y; moved = false; downTime = System.currentTimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    previousX = event.x; previousY = event.y
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 2) moved = true
                    thread { send(JSONObject().put("type", "relative").put("dx", (dx * 1.5).toDouble()).put("dy", (dy * 1.5).toDouble())) }
                }
                MotionEvent.ACTION_UP -> if (!moved && System.currentTimeMillis() - downTime < 450) {
                    thread { send(JSONObject().put("type", "click").put("button", "left")) }
                }
            }
            return true
        }
        lastX = x
        lastY = y
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP -> "up"
            else -> return true
        }
        thread { send(JSONObject().put("type", "mouse").put("x", x.toDouble()).put("y", y.toDouble()).put("action", action)) }
        return true
    }

    private fun playAudio(pcm: ByteArray, rate: Int) {
        if (audioRate != rate || audioPlayer == null) {
            releaseAudio()
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return
            audioPlayer = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(kotlin.math.max(minimum * 3, rate / 4))
                .build()
            audioRate = rate
            audioPlayer?.play()
        }
        audioPlayer?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }
    private fun releaseAudio() {
        try { audioPlayer?.pause(); audioPlayer?.flush(); audioPlayer?.release() } catch (_: Exception) {}
        audioPlayer = null
        audioRate = 0
    }

    private fun setFullscreen(enabled: Boolean) {
        if (enabled && !running) return
        fullScreen = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                               else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        sessionTopBar.visibility = if (enabled) View.GONE else View.VISIBLE
        sessionBottomBar.visibility = if (enabled) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (enabled)
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            else 0
        fullscreenButton.text = if (enabled) "Exit full" else "⛶  Full"
        screen.scaleType = ImageView.ScaleType.FIT_CENTER
        if (Build.VERSION.SDK_INT >= 35) window.decorView.requestApplyInsets()
        else rootLayout.setPadding(0, 0, 0, 0)
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        info("Disconnected")
        runOnUiThread {
            connectButton.isEnabled = true
            if (::remotePage.isInitialized) showDashboard()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the remote session and AudioTrack alive while the user rotates the phone.
        screen.scaleType = ImageView.ScaleType.FIT_CENTER
        screen.requestLayout()
    }

    @Deprecated("Back navigation exits immersive mode or returns to dashboard before closing the app")
    override fun onBackPressed() {
        when {
            fullScreen -> setFullscreen(false)
            remotePageVisible -> showDashboard()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() { disconnect(); releaseAudio(); super.onDestroy() }
}
