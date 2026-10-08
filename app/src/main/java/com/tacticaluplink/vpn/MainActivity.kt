package com.tacticaluplink.vpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tacticaluplink.vpn.core.DiagnosticLog
import com.tacticaluplink.vpn.core.VpnController
import com.tacticaluplink.vpn.wireguard.WireGuardParser
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity(), WebAppInterface.Host {

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect_profile"
    }

    private lateinit var webView: WebView
    private var pendingProfileId: String? = null
    private var pendingEnable = false

    private val wireGuardConfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult
        }
        importWireGuardConfUri(uri)
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val profileId = pendingProfileId
            if (profileId != null && pendingEnable) {
                window.decorView.postDelayed({
                    applyProfileState(profileId, true)
                }, 400)
            }
        } else {
            pendingProfileId = null
            pendingEnable = false
            notifyHud(false, VpnController.get().activeProfileId, "ОТКАЗ VPN: разрешение не выдано")
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val state = intent.getStringExtra(VpnStateReceiver.EXTRA_STATE) ?: return
            val message = intent.getStringExtra(VpnStateReceiver.EXTRA_MESSAGE)
            val profileId = intent.getStringExtra(VpnStateReceiver.EXTRA_PROFILE_ID)
                ?: VpnController.get().activeProfileId

            when (state) {
                VpnStateReceiver.STATE_CONNECTING -> notifyHud(false, profileId, "ИНИЦИАЛИЗАЦИЯ АПЛИНКА...")
                VpnStateReceiver.STATE_CONNECTED -> notifyHud(true, profileId, null)
                VpnStateReceiver.STATE_DISCONNECTED -> notifyHud(false, profileId, "АПЛИНК ОТКЛЮЧЁН")
                VpnStateReceiver.STATE_ERROR -> {
                    notifyHud(false, profileId, message ?: "ОШИБКА ПОДКЛЮЧЕНИЯ")
                    Toast.makeText(
                        this@MainActivity,
                        message ?: getString(R.string.vpn_status_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        webView = findViewById(R.id.hudWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webView.webViewClient = WebViewClient()
        VpnController.get().init(applicationContext)
        webView.addJavascriptInterface(WebAppInterface(this, this), "Android")
        webView.loadUrl("file:///android_asset/index.html")

        logRuntimeIdentity()
        requestPostNotificationsIfNeeded()
        intent?.getStringExtra(EXTRA_AUTO_CONNECT)?.let { profileId ->
            webView.postDelayed({ requestVpnConsentAndConnect(profileId) }, 900)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            IntentFilter(VpnStateReceiver.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(vpnStateReceiver)
        super.onStop()
    }

    override fun onProfileToggleRequested(profileId: String, enabled: Boolean) {
        if (VpnController.get().profiles().get(profileId) == null) {
            DiagnosticLog.e("MainActivity", "Unknown profile: $profileId")
            notifyHud(false, profileId, "НЕИЗВЕСТНЫЙ ПРОФИЛЬ")
            return
        }
        if (enabled) {
            VpnController.get().activeProfileId = profileId
            DiagnosticLog.i("MainActivity", "Connect requested profile=$profileId")
            requestVpnConsentAndConnect(profileId)
        } else if (VpnController.get().activeProfileId == profileId) {
            DiagnosticLog.i("MainActivity", "Disconnect requested profile=$profileId")
            applyProfileState(profileId, false)
        }
    }

    override fun getActiveProfileId(): String {
        return VpnController.get().activeProfileId
    }

    override fun isVpnConnected(): Boolean {
        return VpnController.get().isConnected
    }

    override fun onPickWireGuardConfRequested() {
        wireGuardConfPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
    }

    private fun importWireGuardConfUri(uri: Uri) {
        try {
            val displayName = queryDisplayName(uri) ?: "import.conf"
            val text = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }?.trim() ?: throw IllegalArgumentException("Файл пустой")
            if (text.isEmpty()) {
                throw IllegalArgumentException("Файл пустой")
            }
            WireGuardParser.parse(text)
            deliverWireGuardConfToHud(text, displayName)
            DiagnosticLog.i("MainActivity", "Imported WG conf: $displayName (${text.length} chars)")
        } catch (e: Exception) {
            DiagnosticLog.e("MainActivity", "WG conf import failed", e)
            val msg = e.message ?: "Не удалось прочитать .conf"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            webView.post {
                webView.evaluateJavascript(
                    "if(window.logUi){window.logUi('Импорт .conf: ${escapeJs(msg)}','err');}",
                    null
                )
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        return cursor.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun deliverWireGuardConfToHud(text: String, displayName: String) {
        val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val safeName = escapeJs(displayName)
        webView.post {
            webView.evaluateJavascript(
                "if(window.onWireGuardConfImported){window.onWireGuardConfImported('$b64','$safeName');}",
                null
            )
        }
    }

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "")
            .replace("\n", "\\n")
    }

    private fun logRuntimeIdentity() {
        val userId = Process.myUid() / 100000
        DiagnosticLog.i(
            "MainActivity",
            "uid=${Process.myUid()} userId=$userId — VPN только в основном профиле, не в Dual App"
        )
        if (userId == 95) {
            Toast.makeText(
                this,
                "Это копия Dual App: VPN здесь не работает. Откройте TacticalUplink из основного профиля.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun requestVpnConsentAndConnect(profileId: String) {
        val prepareIntent = VpnService.prepare(this)
        DiagnosticLog.i("MainActivity", "VpnService.prepare -> ${if (prepareIntent == null) "granted" else "needs dialog"}")
        if (prepareIntent != null) {
            pendingProfileId = profileId
            pendingEnable = true
            notifyHud(false, profileId, "ПОДТВЕРДИТЕ VPN В СИСТЕМНОМ ОКНЕ...")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            window.decorView.postDelayed({
                applyProfileState(profileId, true)
            }, 200)
        }
    }

    private fun applyProfileState(profileId: String, enabled: Boolean) {
        pendingProfileId = null
        pendingEnable = false
        VpnController.get().activeProfileId = profileId
        val action = if (enabled) {
            TacticalVpnService.ACTION_CONNECT
        } else {
            TacticalVpnService.ACTION_DISCONNECT
        }
        val serviceIntent = Intent(this, TacticalVpnService::class.java)
            .setAction(action)
            .putExtra(TacticalVpnService.EXTRA_PROFILE_ID, profileId)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun notifyHud(connected: Boolean, profileId: String, logMessage: String?) {
        val safeProfile = profileId.replace("\\", "\\\\").replace("'", "\\'")
        val safeLog = logMessage
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            ?: ""
        val js = buildString {
            append("if(window.onAndroidVpnState){window.onAndroidVpnState(")
            append(connected)
            append(",'")
            append(safeLog)
            append("','")
            append(safeProfile)
            append("');}")
        }
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
