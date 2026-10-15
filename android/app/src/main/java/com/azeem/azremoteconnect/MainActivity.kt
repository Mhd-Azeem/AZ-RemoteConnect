package com.azeem.azremoteconnect

import android.app.Activity
import android.os.Bundle
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
    private lateinit var fullscreenButton: Button
    private lateinit var audioButton: Button
    @Volatile private var audioEnabled = true
    private var audioPlayer: AudioTrack? = null
    private var audioRate = 0
    private lateinit var rootLayout: LinearLayout
    private lateinit var modeBar: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 10)
            setBackgroundColor(Color.rgb(12, 17, 29))
        }
        fun label(value: String): TextView = TextView(this).apply { text = value; setTextColor(Color.WHITE); textSize = 14f }
        fun entry(hintText: String, value: String = ""): EditText = EditText(this).apply {
            hint = hintText; setText(value); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); textSize = 14f
        }
        rootLayout.addView(label("AZ RemoteConnect  •  Secure LAN").apply { textSize = 20f; setTextColor(Color.rgb(230,239,255)); setPadding(8, 8, 8, 12) })
        hostInput = entry("Laptop IP address", "192.168.1.2")
        codeInput = entry("Laptop pairing code (6 digits)")
        fingerprintInput = entry("Laptop verification code (12 characters)")
        settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel.addView(hostInput); settingsPanel.addView(codeInput); settingsPanel.addView(fingerprintInput)
        settingsPanel.setPadding(12, 8, 12, 8)
        settingsPanel.background = GradientDrawable().apply { setColor(Color.rgb(25,34,52)); cornerRadius = 22f }
        rootLayout.addView(settingsPanel)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = Button(this).apply { text = "Connect"; setOnClickListener { connect() } }
        val disconnect = Button(this).apply { text = "Disconnect"; setOnClickListener { disconnect() } }
        controls.addView(connectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(disconnect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(controls)
        status = label("Connect to your Windows laptop on the same Wi-Fi network.")
        rootLayout.addView(status)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyboardInput = entry("Type on laptop")
        tools.addView(keyboardInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        fun toolButton(title: String, action: () -> Unit) {
            tools.addView(Button(this).apply { text = title; textSize=11f; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        toolButton("Send") {
            val value = keyboardInput.text.toString()
            if (value.isNotEmpty()) { thread { send(JSONObject().put("type", "text").put("value", value.take(200))) }; keyboardInput.text.clear() }
        }
        toolButton("⌫") { thread { send(JSONObject().put("type", "key").put("key", "BACKSPACE")) } }
        toolButton("↵") { thread { send(JSONObject().put("type", "key").put("key", "ENTER")) } }
        rootLayout.addView(tools)
        val mouseTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mouseButton(title: String, action: () -> Unit) {
            mouseTools.addView(Button(this).apply { text = title; textSize=11f; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        mouseButton("Right click") {
            thread { send(JSONObject().put("type", "mouse").put("x", lastX.toDouble()).put("y", lastY.toDouble()).put("action", "right")) }
        }
        mouseButton("Scroll ↑") { thread { send(JSONObject().put("type", "scroll").put("delta", 120)) } }
        mouseButton("Scroll ↓") { thread { send(JSONObject().put("type", "scroll").put("delta", -120)) } }
        rootLayout.addView(mouseTools)
        modeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeButton = Button(this).apply {
            text = "Screen Touch"
            setOnClickListener {
                touchpadMode = !touchpadMode
                text = if (touchpadMode) "Touchpad" else "Screen Touch"
            }
        }
        fullscreenButton = Button(this).apply { text = "Full screen"; setOnClickListener { setFullscreen(!fullScreen) } }
        modeBar.addView(modeButton, LinearLayout.LayoutParams(0, -2, 1f))
        modeBar.addView(fullscreenButton, LinearLayout.LayoutParams(0, -2, 1f))
        audioButton = Button(this).apply {
            text = "Audio: On"
            setOnClickListener {
                audioEnabled = !audioEnabled
                text = if (audioEnabled) "Audio: On" else "Audio: Off"
                if (!audioEnabled) { audioPlayer?.pause(); audioPlayer?.flush() } else audioPlayer?.play()
            }
        }
        modeBar.addView(audioButton, LinearLayout.LayoutParams(0, -2, 1f))
        rootLayout.addView(modeBar)
        screen = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            adjustViewBounds = true
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        rootLayout.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        rootLayout.addView(label("Pairing approval required. Tap, drag, right click, keyboard and scroll. Fast JPEG is best-effort, not guaranteed 60 FPS."))
        setContentView(rootLayout)
    }

    private fun info(message: String) = runOnUiThread { status.text = message }

    private fun connect() {
        if (running) return
        val ip = hostInput.text.toString().trim()
        val code = codeInput.text.toString().trim()
        val pin = fingerprintInput.text.toString().replace(":", "").replace(" ", "").uppercase()
        if (!code.matches(Regex("[0-9]{6}")) || !pin.matches(Regex("[0-9A-F]{12}"))) {
            info("Enter the 6-digit code and the 12-character verification code shown on the laptop.")
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
                if (actual.take(12) != pin) throw SecurityException("Fingerprint mismatch. Connection rejected.")
                val input = DataInputStream(BufferedInputStream(ssl.inputStream))
                output = DataOutputStream(BufferedOutputStream(ssl.outputStream))
                send(JSONObject().put("type", "pair").put("code", code))
                val approval = read(input)
                if (approval.optString("type") != "approved") throw IOException("Host rejected or closed the pairing request")
                ssl.soTimeout = 0
                info("Connected. Remote screen active.")
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
                runOnUiThread { connectButton.isEnabled = true }
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
        fullScreen = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                               else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        for (i in 0 until rootLayout.childCount) {
            val child = rootLayout.getChildAt(i)
            if (child !== screen && child !== modeBar)
                child.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        }
        window.decorView.systemUiVisibility = if (enabled)
            (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else 0
        fullscreenButton.text = if (enabled) "Exit full screen" else "Full screen"
        screen.scaleType = ImageView.ScaleType.FIT_CENTER
        rootLayout.setPadding(if (enabled) 0 else 16, if (enabled) 0 else 14,
            if (enabled) 0 else 16, if (enabled) 0 else 10)
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        info("Disconnected")
        runOnUiThread { connectButton.isEnabled = true }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the remote session and AudioTrack alive while the user rotates the phone.
        screen.scaleType = ImageView.ScaleType.FIT_CENTER
        screen.requestLayout()
    }

    override fun onDestroy() { disconnect(); releaseAudio(); super.onDestroy() }
}
