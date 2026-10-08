package com.tacticaluplink.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.tacticaluplink.vpn.core.DiagnosticLog;
import com.tacticaluplink.vpn.core.ProfileStore;
import com.tacticaluplink.vpn.core.VpnController;
import com.tacticaluplink.vpn.core.StoredProfile;
import com.tacticaluplink.vpn.core.VpnPathProbe;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

public final class TacticalVpnService extends VpnService {

    private static final String TAG = "TacticalVpnService";
    public static final String ACTION_CONNECT = "com.tacticaluplink.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.tacticaluplink.vpn.DISCONNECT";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "tactical_uplink_vpn";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CoreController coreController;
    private ParcelFileDescriptor vpnInterface;
    private volatile boolean starting;
    private volatile String lastTunnelError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        ensureCoreEnv();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_DISCONNECT.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(intent.getAction())) {
            String profileId = intent.getStringExtra(EXTRA_PROFILE_ID);
            if (profileId != null) {
                VpnController.get().setActiveProfileId(profileId);
            }
            if (coreController != null && coreController.getIsRunning()) {
                closeResources(true);
            }
            startForegroundWithType(buildNotification(getString(R.string.vpn_status_connecting), false));
            broadcastState(VpnStateReceiver.STATE_CONNECTING, null);
            new Thread(this::startVpn, "tactical-vpn-start").start();
        }

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        closeResources(false);
        super.onDestroy();
    }

    private void ensureCoreEnv() {
        Seq.setContext(getApplicationContext());
        String assetPath = getFilesDir().getAbsolutePath();
        Libv2ray.initCoreEnv(assetPath, getXudpBaseKey());
    }

    /** Same scheme as v2rayNG: 32-byte key, Base64 URL-safe without padding. */
    private String getXudpBaseKey() {
        String seed = null;
        try {
            seed = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (RuntimeException e) {
            DiagnosticLog.w("VpnService", "ANDROID_ID unavailable: " + e.getMessage());
        }
        if (seed == null || seed.isEmpty()) {
            seed = getSharedPreferences("tactical_uplink_core", MODE_PRIVATE)
                    .getString("xudp_seed", null);
            if (seed == null || seed.isEmpty()) {
                seed = UUID.randomUUID().toString();
                getSharedPreferences("tactical_uplink_core", MODE_PRIVATE)
                        .edit()
                        .putString("xudp_seed", seed)
                        .apply();
                DiagnosticLog.i("VpnService", "Generated persistent xudp seed");
            }
        }
        byte[] source = seed.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        System.arraycopy(source, 0, key, 0, Math.min(source.length, 32));
        return Base64.encodeToString(key, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private void startVpn() {
        starting = true;
        try {
            VpnController.get().init(this);
            String profileId = VpnController.get().getActiveProfileId();
            DiagnosticLog.i("VpnService", "Starting VPN profile=" + profileId
                    + " uid=" + Process.myUid() + " user=" + (Process.myUid() / 100000));
            if (!establishTunnel()) {
                String detail = lastTunnelError != null && !lastTunnelError.isBlank()
                        ? lastTunnelError
                        : "VPN interface was not established";
                throw new IllegalStateException(detail);
            }

            String json = VpnController.get().buildActiveXrayJson();
            DiagnosticLog.d("VpnService", "Xray JSON length=" + json.length());

            coreController = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override
                public long startup() {
                    return 0;
                }

                @Override
                public long shutdown() {
                    mainHandler.post(TacticalVpnService.this::stopVpn);
                    return 0;
                }

                @Override
                public long onEmitStatus(long code, String message) {
                    Log.d(TAG, "xray: " + message);
                    if (message != null && !message.isBlank()) {
                        DiagnosticLog.d("XrayCore", "status[" + code + "]: " + message);
                    }
                    return 0;
                }
            });

            coreController.startLoop(json, vpnInterface.getFd());
            if (!coreController.getIsRunning()) {
                throw new IllegalStateException("Xray core failed to start");
            }

            VpnController.get().setConnected(true);
            if (profileId != null && VpnController.get().profiles().get(profileId) != null
                    && StoredProfile.TYPE_WIREGUARD.equals(
                    VpnController.get().profiles().get(profileId).type)) {
                DiagnosticLog.i("VpnService", "VPN connected profile=" + profileId + " engine=xray-wg-ip-only");
            } else {
                DiagnosticLog.i("VpnService", "VPN connected profile=" + profileId);
            }
            final String probeJson = json;
            final CoreController probeCore = coreController;
            new Thread(
                    () -> VpnPathProbe.runAfterConnect(getApplicationContext(), probeCore, profileId, probeJson),
                    "vpn-path-probe"
            ).start();
            mainHandler.post(() -> {
                updateNotification(getString(R.string.vpn_status_connected), true);
                broadcastState(VpnStateReceiver.STATE_CONNECTED, null);
            });
        } catch (Exception e) {
            Log.e(TAG, "VPN start failed", e);
            DiagnosticLog.e("VpnService", "VPN start failed", e);
            String message = e.getMessage() != null ? e.getMessage() : "VPN start failed";
            mainHandler.post(() -> {
                broadcastState(VpnStateReceiver.STATE_ERROR, message);
                stopVpn();
            });
        } finally {
            starting = false;
        }
    }

    private boolean establishTunnel() {
        if (vpnInterface != null) {
            return true;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return establishTunnelOnMainThread();
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        AtomicReference<String> err = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                ok.set(establishTunnelOnMainThread());
            } catch (Exception e) {
                err.set(e.getMessage());
                DiagnosticLog.e("VpnService", "establish on main thread failed", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                DiagnosticLog.e("VpnService", "establishTunnel timeout waiting main thread");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (err.get() != null) {
            DiagnosticLog.e("VpnService", "establishTunnel error: " + err.get());
        }
        return Boolean.TRUE.equals(ok.get());
    }

    private boolean establishTunnelOnMainThread() {
        if (vpnInterface != null) {
            return true;
        }
        lastTunnelError = "";

        VpnController.get().init(this);
        String profileId = VpnController.get().getActiveProfileId();
        ProfileStore.DnsPair dns = VpnController.get().dnsForActiveProfile();
        String primaryDns = dns.primary;
        String dnsScope = "nx62".equals(profileId) ? "home NX62" : "remote router";
        int tunMtu = tunMtuForProfile(profileId);
        DiagnosticLog.i("VpnService", "TUN profile=" + profileId + " dns=" + primaryDns
                + (dns.secondary != null && !dns.secondary.isBlank() ? " xrayDns=" + dns.secondary : "")
                + " mtu=" + tunMtu + " scope=" + dnsScope + " ownAppExcludedFromTun=true");

        // Do not call prepare() here — on Samsung it often returns non-null from the service
        // even after the user granted VPN in the activity. Permission is requested in MainActivity only.
        for (int attempt = 1; attempt <= 5; attempt++) {
            Builder builder = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(tunMtu)
                    .addAddress("26.26.26.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(primaryDns)
                    .setBlocking(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
                builder.allowFamily(OsConstants.AF_INET);
            }

            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not disallow own app from VPN", e);
            }

            try {
                vpnInterface = builder.establish();
                if (vpnInterface != null) {
                    DiagnosticLog.i("VpnService", "TUN established attempt=" + attempt);
                    return true;
                }
                lastTunnelError = "establish=null — подтвердите VPN в системном окне или отключите другой VPN";
                DiagnosticLog.e("VpnService", "establish() null attempt=" + attempt);
            } catch (Exception e) {
                lastTunnelError = "TUN: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e(TAG, "Failed to establish VPN tunnel", e);
                DiagnosticLog.e("VpnService", "Failed to establish VPN tunnel", e);
                return false;
            }

            if (attempt < 5) {
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static int tunMtuForProfile(String profileId) {
        if (profileId == null) {
            return 1500;
        }
        StoredProfile profile = VpnController.get().profiles().get(profileId);
        if (profile != null && StoredProfile.TYPE_WIREGUARD.equals(profile.type)) {
            return 1420;
        }
        return 1500;
    }

    private void startForegroundWithType(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopVpn() {
        DiagnosticLog.i("VpnService", "Stopping VPN");
        VpnController.get().setConnected(false);
        closeResources(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
        broadcastState(VpnStateReceiver.STATE_DISCONNECTED, null);
    }

    private void closeResources(boolean stopCore) {
        if (stopCore && coreController != null) {
            try {
                if (coreController.getIsRunning()) {
                    coreController.stopLoop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop Xray core", e);
            }
        }
        coreController = null;

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close TUN", e);
            }
            vpnInterface = null;
        }
    }

    private void broadcastState(String state, String message) {
        Intent intent = new Intent(VpnStateReceiver.ACTION);
        intent.setPackage(getPackageName());
        intent.putExtra(VpnStateReceiver.EXTRA_STATE, state);
        intent.putExtra(VpnStateReceiver.EXTRA_MESSAGE, message);
        intent.putExtra(VpnStateReceiver.EXTRA_PROFILE_ID, VpnController.get().getActiveProfileId());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.vpn_channel_desc));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean connected) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent disconnectIntent = new Intent(this, TacticalVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPending = PendingIntent.getService(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(connected)
                .setOnlyAlertOnce(true);

        if (connected) {
            builder.addAction(0, getString(R.string.vpn_action_disconnect), disconnectPending);
        }

        return builder.build();
    }

    private void updateNotification(String text, boolean connected) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text, connected));
        }
    }
}
