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
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
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
        const val EXTRA_ALLOW_REMOTE_TOUCH = "allowRemoteTouch"
        const val CHANNEL = "az_remote_host"
        @Volatile var code = ""
        @Volatile var active = false
        @Volatile var hostError = ""
        @Volatile var hostStatus = "Not sharing"
        @Volatile var activeClient = false
    }
    private var server: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private val accepting = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val sessionBusy = AtomicBoolean(false)
    @Volatile private var pendingApproval = false
    @Volatile private var approved: Boolean? = null
    private val approvalLock = Object()
    private var frameWidth = 0
    private var touchAllowedForSession = false
    private var frameHeight = 0
    private var pointerX = 0.5f
    private var pointerY = 0.5f
    private val rejectedPairs = java.util.concurrent.atomic.AtomicInteger(0)
    private fun failure(phase: String, error: Exception) {
        hostError = "$phase: ${error.javaClass.simpleName}: ${error.message}"
        hostStatus = hostError
        android.util.Log.e("AZRemoteHost", hostError, error)
    }

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
                    if (!pendingApproval) return START_NOT_STICKY
                    approved = intent.action == ACTION_APPROVE
                    pendingApproval = false
                    approvalLock.notifyAll()
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(42, notice("Active host session"))
                return START_NOT_STICKY
            }
        }
        if (active || !starting.compareAndSet(false, true)) return START_NOT_STICKY
        hostError = ""
        hostStatus = "Preparing Android screen sharing"
        rejectedPairs.set(0)
        code = ""
        touchAllowedForSession = intent?.getBooleanExtra(EXTRA_ALLOW_REMOTE_TOUCH, false) == true &&
            RemoteAccessibility.instance != null
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
                override fun onStop() {
                    hostError = "Android ended screen capture. Start a new sharing session."
                    hostStatus = hostError
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            display = projection?.createVirtualDisplay("AZ remote screen", frameWidth, frameHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
            Thread({ serve() }, "az-android-host").start()
        } catch (e: Exception) {
            starting.set(false)
            failure("Screen capture setup", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun makeTls(): SSLServerSocketFactory {
        // Use an AndroidKeyStore-backed host identity (private key never exported).
        // The TLS 1.2 ECDHE_RSA/GCM profile is explicit on both Windows and Android:
        // this avoids TLS 1.3 RSA-PSS incompatibilities with OEM Keystore drivers.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "AZRemoteConnectHostTLSv2"
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=AZRemoteConnect-Temporary"))
                .build())
            generator.generateKeyPair()
        }
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("Android TLS certificate is missing")
        val privateKey = store.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Android TLS private key is unavailable")

        // Some OEMs' KeyManagerFactory implementations don't expose hardware-backed
        // keys as usable server aliases. Select the verified host alias explicitly.
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?) = null
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?) =
                if (keyType == "RSA") arrayOf(alias) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?) =
                if (keyType == "RSA") alias else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, engine: SSLEngine?) =
                if (keyType == "RSA") alias else null
            override fun getCertificateChain(requested: String?): Array<X509Certificate>? =
                if (requested == alias) arrayOf(certificate) else null
            override fun getPrivateKey(requested: String?): PrivateKey? =
                if (requested == alias) privateKey else null
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(keyManager), null, SecureRandom())
        }.serverSocketFactory
    }

    private val allowedCipherSuites = setOf(
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    )

    private fun configureServerTls(socket: SSLServerSocket) {
        val usable = socket.supportedCipherSuites.filter { it in allowedCipherSuites }
        if (usable.isEmpty()) throw IllegalStateException(
            "This phone has no supported ECDHE_RSA_AES_GCM TLS 1.2 suites")
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.enabledCipherSuites = usable.toTypedArray()
        socket.needClientAuth = false
    }

    private fun testLocalTls(factory: SSLServerSocketFactory) {
        // A real loopback TLS negotiation checks that the generated hardware-backed
        // certificate can actually sign a handshake. No data or pairing code is sent.
        val cert = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getCertificate("AZRemoteConnectHostTLSv2") as X509Certificate
        val expected = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val receiver = factory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        configureServerTls(receiver)
        receiver.soTimeout = 6500
        val serverError = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                (receiver.accept() as SSLSocket).use { peer ->
                    peer.enabledProtocols = arrayOf("TLSv1.2")
                    peer.soTimeout = 5500
                    peer.startHandshake()
                }
            } catch (error: Throwable) { serverError.set(error) }
        }, "az-host-tls-self-test")
        worker.isDaemon = true
        try {
            worker.start()
            val pinTrust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Client certificate auth is not supported")
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val received = chain.firstOrNull() ?: throw CertificateException("Missing TLS certificate")
                    val digest = MessageDigest.getInstance("SHA-256").digest(received.encoded)
                    if (!MessageDigest.isEqual(expected, digest))
                        throw CertificateException("Local TLS test certificate did not match the host")
                }
            }
            val testContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(pinTrust), SecureRandom())
            }
            (testContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), receiver.localPort) as SSLSocket)
                .use { client ->
                    client.enabledProtocols = arrayOf("TLSv1.2")
                    client.soTimeout = 5500
                    client.startHandshake()
                }
            worker.join(5500)
            serverError.get()?.let { throw IllegalStateException("Server TLS self-test error: ${it.message}", it) }
            if (worker.isAlive) throw IllegalStateException("Server TLS self-test timed out")
        } finally {
            receiver.close()
        }
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
            hostStatus = "Initializing TLS host certificate"
            val factory = makeTls()
            hostStatus = "Verifying local encrypted handshake"
            testLocalTls(factory)
            val listener = factory.createServerSocket(48765) as SSLServerSocket
            configureServerTls(listener)
            server = listener
            code = (100000 + SecureRandom().nextInt(900000)).toString()
            active = true
            accepting.set(true)
            starting.set(false)
            hostStatus = "Ready for incoming connections on port 48765"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(42, notice("Code: $code • " +
                    (if (touchAllowedForSession) "Screen + touch" else "Screen view only")))
            while (accepting.get()) {
                val client = listener.accept() as? SSLSocket ?: break
                if (!sessionBusy.compareAndSet(false, true)) {
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                Thread({
                    try { handle(client) }
                    finally { activeClient = false; sessionBusy.set(false) }
                }, "az-android-client").start()
            }
        } catch (e: Exception) {
            if (starting.get() || accepting.get()) failure("Host listener or TLS", e)
            active = false
            starting.set(false)
            accepting.set(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(42, notice("Host failed • open app and tap Refresh code"))
            // Failed TLS or socket setup must not leave MediaProjection capturing in the background.
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }
    private fun handle(client: SSLSocket) {
        var stage = "TLS handshake"
        client.use { ssl ->
            try {
                ssl.soTimeout = 30000
                ssl.startHandshake()
                // A failed TLS negotiation occurs before code verification and before approval.
                hostError = ""
                stage = "Pairing code"
                val input = DataInputStream(BufferedInputStream(ssl.inputStream))
                val output = DataOutputStream(BufferedOutputStream(ssl.outputStream))
                val pair = read(input)
                if (rejectedPairs.get() >= 10 || pair.optString("type") != "pair" || pair.optString("code") != code) {
                    rejectedPairs.incrementAndGet()
                    send(output, JSONObject().put("type", "error").put("message", "Incorrect pairing code"))
                    return
                }
                stage = "Phone-side approval"
                synchronized(approvalLock) {
                    approved = null
                    pendingApproval = true
                }
                hostStatus = "Awaiting approval on phone notification"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(42, notice(if (touchAllowedForSession) "Request: screen + remote touch" else "Request: view screen only", true))
                synchronized(approvalLock) {
                    val until = System.currentTimeMillis() + 45000
                    while (approved == null && System.currentTimeMillis() < until)
                        approvalLock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
                    if (approved != true) {
                        pendingApproval = false
                        send(output, JSONObject().put("type", "error").put("message",
                            if (approved == false) "Phone denied access" else "Phone approval timed out after 45 seconds"))
                        return
                    }
                }
                pendingApproval = false
                ssl.soTimeout = 0
                stage = "Screen capture streaming"
                send(output, JSONObject().put("type", "approved"))
                activeClient = true
                hostError = ""
                hostStatus = "Approved • streaming to remote viewer"
                Thread({
                    try {
                        while (active && !ssl.isClosed) {
                            val command = read(input)
                            val service = if (touchAllowedForSession) RemoteAccessibility.instance else null
                            if (service == null) continue
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
                                "text" -> service.replaceFocusedText(command.optString("value").take(200))
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
                if (stage == "TLS handshake") {
                    val underlying = generateSequence(e as Throwable?) { it.cause }.last()
                    hostError = "TLS 1.2 handshake failed: " +
                        underlying.javaClass.simpleName + ": " +
                        (underlying.message ?: "peer closed the encrypted connection").take(220) +
                        ". A raw port test also triggers a harmless 'connection closed' message."
                    // A failed incoming negotiation does not stop the listener.
                    hostStatus = "Ready for incoming connections on port 48765"
                    android.util.Log.w("AZRemoteHost", "TLS negotiation aborted by remote peer", e)
                } else failure(stage, e)
            } finally {
                activeClient = false
                synchronized(approvalLock) { pendingApproval = false; approvalLock.notifyAll() }
                if (active) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(42, notice("Code: $code • Waiting for a viewer"))
                }
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
        starting.set(false)
        activeClient = false
        pendingApproval = false
        hostStatus = "Sharing stopped"
        code = ""
        touchAllowedForSession = false
        try { server?.close() } catch (_: Exception) {}
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
