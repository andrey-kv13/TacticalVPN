package com.tacticaluplink.vpn.core;

import android.content.Context;
import android.provider.Settings;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Post-connect checks to locate DNS / proxy / HTTP failures (no guessing in UI).
 */
public final class VpnPathProbe {

    private static final String[] PROBE_HOSTS = {
            "www.google.com",
            "www.youtube.com",
            "m.youtube.com",
            "i.ytimg.com",
            "api.telegram.org"
    };

    private static final String[] PROBE_URLS = {
            "https://www.google.com/generate_204",
            "https://www.youtube.com/generate_204",
            "https://www.youtube.com/",
            "https://m.youtube.com/",
            "https://api.telegram.org/"
    };

    private VpnPathProbe() {
    }

    public static void runAfterConnect(Context context, CoreController core, String profileId, String xrayJson) {
        DiagnosticLog.i("PathProbe", "=== START profile=" + profileId + " lib=" + safe(Libv2ray.checkVersionX()) + " ===");
        logAndroidDnsPolicy(context);
        DiagnosticLog.i(
                "PathProbe",
                "NOTE: TacticalUplink is addDisallowedApplication — InetAddress/HttpURLConnection here "
                        + "use the default network, NOT the VPN tunnel (YouTube/other apps differ)."
        );
        logDnsSystem();
        logHttpHead(PROBE_URLS);
        if (core != null) {
            logCoreDelay(core, new String[]{
                    "https://www.google.com/generate_204",
                    "https://www.youtube.com/generate_204"
            });
            logCoreStats(core);
        }
        logOutboundDelay(xrayJson, "https://www.youtube.com/generate_204");
        DiagnosticLog.i("PathProbe", "=== END profile=" + profileId + " ===");
    }

    private static void logAndroidDnsPolicy(Context context) {
        try {
            String mode = Settings.Global.getString(context.getContentResolver(), "private_dns_mode");
            String spec = Settings.Global.getString(context.getContentResolver(), "private_dns_specifier");
            DiagnosticLog.i(
                    "PathProbe",
                    "Android private_dns_mode=" + safe(mode) + " specifier=" + safe(spec)
                            + " (strict hostname often bypasses TUN DNS → real Google IPs, YouTube breaks on nx62)"
            );
        } catch (Exception e) {
            DiagnosticLog.e("PathProbe", "private_dns read FAIL", e);
        }
    }

    private static void logDnsSystem() {
        for (String host : PROBE_HOSTS) {
            try {
                long t0 = System.nanoTime();
                InetAddress[] addrs = InetAddress.getAllByName(host);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                StringBuilder sb = new StringBuilder();
                boolean anyFake = false;
                boolean anyV6 = false;
                for (int i = 0; i < addrs.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    String ip = addrs[i].getHostAddress();
                    sb.append(ip);
                    if (ip != null && ip.contains(":")) {
                        anyV6 = true;
                    }
                    if (isFakeIp19818(ip)) {
                        anyFake = true;
                    }
                }
                String verdict = anyFake
                        ? "OK fakeip19818 (podkop path)"
                        : (anyV6 ? "WARN ipv6+public (not podkop fakeip)" : "WARN public-ipv4 (not podkop fakeip)");
                DiagnosticLog.i("PathProbe", "DNS java " + host + " -> " + sb + " (" + ms + "ms) " + verdict);
            } catch (Exception e) {
                DiagnosticLog.e("PathProbe", "DNS java " + host + " FAIL", e);
            }
        }
    }

    private static boolean isFakeIp19818(String ip) {
        if (ip == null || ip.contains(":")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 198 && b >= 18 && b <= 19;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void logHttpHead(String[] urls) {
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                long t0 = System.nanoTime();
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("HEAD");
                conn.setRequestProperty("User-Agent", "TacticalUplink-Probe/1.0");
                int code = conn.getResponseCode();
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                String loc = conn.getHeaderField("Location");
                String via = conn.getHeaderField("Via");
                DiagnosticLog.i(
                        "PathProbe",
                        "HTTP HEAD " + urlStr + " -> " + code
                                + " in " + ms + "ms (off-tunnel)"
                                + (loc != null ? " loc=" + loc : "")
                                + (via != null ? " via=" + via : "")
                );
                if (code >= 400) {
                    logErrorBody(conn);
                }
            } catch (Exception e) {
                DiagnosticLog.e("PathProbe", "HTTP HEAD " + urlStr + " FAIL (off-tunnel)", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private static void logErrorBody(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            if (in == null) {
                return;
            }
            byte[] buf = in.readNBytes(256);
            String snippet = new String(buf, StandardCharsets.UTF_8).replace('\n', ' ');
            DiagnosticLog.w("PathProbe", "HTTP err body: " + snippet);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void logCoreDelay(CoreController core, String[] urls) {
        for (String url : urls) {
            try {
                long ms = core.measureDelay(url);
                DiagnosticLog.i("PathProbe", "Xray measureDelay " + url + " -> " + ms + "ms (through tun/core)");
            } catch (Exception e) {
                DiagnosticLog.e("PathProbe", "Xray measureDelay " + url + " FAIL", e);
            }
        }
    }

    private static void logCoreStats(CoreController core) {
        try {
            String stats = core.queryAllOutboundTrafficStats();
            DiagnosticLog.i("PathProbe", "Xray traffic stats: " + (stats != null && !stats.isBlank() ? stats.trim() : "(empty)"));
        } catch (Exception e) {
            DiagnosticLog.e("PathProbe", "Xray traffic stats FAIL", e);
        }
        try {
            long up = core.queryStats("proxy", "uplink");
            long down = core.queryStats("proxy", "downlink");
            long dup = core.queryStats("direct", "uplink");
            long ddown = core.queryStats("direct", "downlink");
            DiagnosticLog.i("PathProbe", "Xray proxy up/down=" + up + "/" + down + " direct up/down=" + dup + "/" + ddown);
        } catch (Exception e) {
            DiagnosticLog.e("PathProbe", "Xray queryStats FAIL", e);
        }
    }

    private static void logOutboundDelay(String xrayJson, String url) {
        try {
            long ms = Libv2ray.measureOutboundDelay(xrayJson, url);
            DiagnosticLog.i("PathProbe", "Lib measureOutboundDelay " + url + " -> " + ms + "ms (isolated core)");
        } catch (Exception e) {
            DiagnosticLog.e("PathProbe", "Lib measureOutboundDelay FAIL", e);
        }
    }

    private static String safe(String s) {
        return s != null ? s : "?";
    }
}
