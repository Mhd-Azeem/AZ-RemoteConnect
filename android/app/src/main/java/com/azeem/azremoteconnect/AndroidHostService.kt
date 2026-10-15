package com.azeem.azremoteconnect

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

class AndroidHostService : Service() {
    companion object {
        const val ACTION_START = "com.azeem.azremoteconnect.HOST_START"
        const val ACTION_STOP = "com.azeem.azremoteconnect.HOST_STOP"
        const val ACTION_APPROVE = "com.azeem.azremoteconnect.HOST_APPROVE"
        const val ACTION_REJECT = "com.azeem.azremoteconnect.HOST_REJECT"
        const val EXTRA_RESULT = "projectionResult"
        const val EXTRA_DATA = "projectionData"
        const val CHANNEL = "az_remote_host"
        @Volatile var code = ""
        @Volatile var verification = ""
        @Volatile var active = false
        @Volatile var hostError = ""
    }
    private var server: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private val accepting = AtomicBoolean(false)
    @Volatile private var approved: Boolean? = null
    private val approvalLock = Object()
    private var frameWidth = 0
    private var frameHeight = 0
    private var pointerX = 0.5f
    private var pointerY = 0.5f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "AZ RemoteConnect sharing", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notice(message: String, waiting: Boolean = false): Notification {
        val stop = PendingIntent.getService(this, 11,
            Intent(this, AndroidHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("AZ RemoteConnect • Screen sharing")
            .setContentText(message).setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (waiting) {
            val yes = PendingIntent.getService(this, 12, Intent(this, AndroidHostService::class.java).setAction(ACTION_APPROVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val no = PendingIntent.getService(this, 13, Intent(this, AndroidHostService::class.java).setAction(ACTION_REJECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Approve", yes).build())
            builder.addAction(Notification.Action.Builder(null, "Deny", no).build())
        }
        return builder.build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_APPROVE, ACTION_REJECT -> {
                synchronized(approvalLock) {
                    approved = intent.action == ACTION_APPROVE
                    approvalLock.notifyAll()
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(42, notice("Active host session"))
                return START_NOT_STICKY
            }
        }
        if (active) return START_NOT_STICKY
        startForeground(42, notice("Preparing Android screen sharing"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent?.getParcelableExtra(EXTRA_DATA)
            if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = resources.displayMetrics
            frameWidth = metrics.widthPixels
            frameHeight = metrics.heightPixels
            reader = ImageReader.newInstance(frameWidth, frameHeight, android.graphics.PixelFormat.RGBA_8888, 2)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            display = projection?.createVirtualDisplay("AZ remote screen", frameWidth, frameHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
            code = (100000 + SecureRandom().nextInt(900000)).toString()
            active = true
            accepting.set(true)
            Thread({ serve() }, "az-android-host").start()
        } catch (e: Exception) { hostError = "Screen capture start: ${e.javaClass.simpleName}: ${e.message}"; android.util.Log.e("AZRemoteHost", hostError, e); stopSelf() }
        return START_NOT_STICKY
    }
    private fun makeTls(): SSLServerSocketFactory {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "AZRemoteConnectHost"
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=AZRemoteConnect-Temporary"))
                .build())
            generator.generateKeyPair()
        }
        val cert = store.getCertificate(alias)
        verification = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }.take(6)
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(store, null)
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, SecureRandom()) }.serverSocketFactory
    }
    private fun read(input: DataInputStream): JSONObject {
        val length = input.readInt()
        if (length < 1 || length > 8 * 1024 * 1024) throw IOException("Frame size invalid")
        return JSONObject(String(ByteArray(length).also { input.readFully(it) }, Charsets.UTF_8))
    }
    private fun send(output: DataOutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    private fun serve() {
        try {
            val factory = makeTls()
            server = factory.createServerSocket(48765)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(42, notice("Code: $code • Verify: $verification • Port 48765"))
            while (accepting.get()) {
                val client = server?.accept() as? SSLSocket ?: break
                Thread({ handle(client) }, "az-android-client").start()
            }
        } catch (e: Exception) { hostError = "Host server: ${e.javaClass.simpleName}: ${e.message}"; android.util.Log.e("AZRemoteHost", hostError, e); active = false }
    }
    private fun handle(client: SSLSocket) {
        client.use { ssl ->
            try {
                ssl.soTimeout = 30000
                ssl.startHandshake()
                val input = DataInputStream(BufferedInputStream(ssl.inputStream))
                val output = DataOutputStream(BufferedOutputStream(ssl.outputStream))
                val pair = read(input)
                if (pair.optString("type") != "pair" || pair.optString("code") != code) {
                    send(output, JSONObject().put("type", "error").put("message", "Incorrect pairing code"))
                    return
                }
                synchronized(approvalLock) { approved = null }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(42, notice("Remote device requests control • approve or deny", true))
                synchronized(approvalLock) {
                    val until = System.currentTimeMillis() + 30000
                    while (approved == null && System.currentTimeMillis() < until)
                        approvalLock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
                    if (approved != true) {
                        send(output, JSONObject().put("type", "error").put("message",
                            if (approved == false) "Host denied access" else "Host approval timed out"))
                        return
                    }
                }
                ssl.soTimeout = 0
                send(output, JSONObject().put("type", "approved"))
                Thread({
                    try {
                        while (active && !ssl.isClosed) {
                            val command = read(input)
                            val service = RemoteAccessibility.instance ?: continue
                            val kind = command.optString("type")
                            val x = command.optDouble("x", 0.5).coerceIn(0.0, 1.0).toFloat() * frameWidth
                            val y = command.optDouble("y", 0.5).coerceIn(0.0, 1.0).toFloat() * frameHeight
                            when (kind) {
                                "mouse" -> {
                                    pointerX = x / frameWidth; pointerY = y / frameHeight
                                    if (command.optString("action") == "up") service.click(x, y)
                                }
                                "relative" -> {
                                    pointerX = (pointerX + command.optDouble("dx", 0.0).toFloat() / frameWidth).coerceIn(0f, 1f)
                                    pointerY = (pointerY + command.optDouble("dy", 0.0).toFloat() / frameHeight).coerceIn(0f, 1f)
                                }
                                "click" -> service.click(frameWidth * pointerX, frameHeight * pointerY)
                            }
                        }
                    } catch (_: Exception) { try { ssl.close() } catch (_: Exception) {} }
                }, "az-host-input").start()
                var frameAt = System.currentTimeMillis()
                while (active && !ssl.isClosed) {
                    if (System.currentTimeMillis() - frameAt >= 120) {
                        frameAt = System.currentTimeMillis()
                        val jpg = captureFrame()
                        if (jpg != null) send(output, JSONObject().put("type", "frame")
                            .put("jpeg", Base64.encodeToString(jpg, Base64.NO_WRAP)))
                    }
                    Thread.sleep(8)
                }
            } catch (e: Exception) {
                hostError = "Session: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.e("AZRemoteHost", hostError, e)
            }
        }
    }
    private fun captureFrame(): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowWidth = plane.rowStride / plane.pixelStride
            val bitmap = Bitmap.createBitmap(rowWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            val scale = kotlin.math.min(1f, 960f / kotlin.math.max(image.width, image.height))
            val small = Bitmap.createScaledBitmap(cropped,
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1), true)
            ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 55, it) }.toByteArray()
                .also { small.recycle(); cropped.recycle(); bitmap.recycle() }
        } finally { image.close() }
    }
    override fun onDestroy() {
        active = false; accepting.set(false)
        try { server?.close() } catch (_: Exception) {}
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
