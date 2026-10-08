package com.tacticaluplink.vpn.vless;

import android.net.Uri;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class VlessParser {

    private VlessParser() {
    }

    public static VlessConfig parse(String shareUrl) {
        if (shareUrl == null || shareUrl.isBlank()) {
            throw new IllegalArgumentException("VLESS URL is empty");
        }

        String trimmed = shareUrl.trim();
        if (!trimmed.startsWith("vless://")) {
            throw new IllegalArgumentException("Expected vless:// URL");
        }

        Uri uri = Uri.parse(trimmed);
        String userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            throw new IllegalArgumentException("UUID is missing");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Server host is missing");
        }

        int port = uri.getPort();
        if (port <= 0) {
            port = 443;
        }

        String security = query(uri, "security", "none");
        String sni = query(uri, "sni", host);
        String fingerprint = query(uri, "fp", "chrome");
        String publicKey = query(uri, "pbk", "");
        String shortId = query(uri, "sid", "");
        String transport = query(uri, "type", "tcp");
        String path = query(uri, "path", "/");
        String xhttpMode = query(uri, "mode", "auto");
        String xPaddingBytes = query(uri, "x_padding_bytes", "100-1000");
        String scMaxEachPostBytes = "1000000";
        String remark = uri.getFragment() != null ? uri.getFragment() : "profile";

        String extra = query(uri, "extra", null);
        if (extra != null && !extra.isBlank()) {
            try {
                JSONObject json = new JSONObject(extra);
                if (json.has("mode")) {
                    xhttpMode = json.optString("mode", xhttpMode);
                }
                if (json.has("xPaddingBytes")) {
                    xPaddingBytes = json.optString("xPaddingBytes", xPaddingBytes);
                }
                if (json.has("scMaxEachPostBytes")) {
                    scMaxEachPostBytes = json.optString("scMaxEachPostBytes", scMaxEachPostBytes);
                }
            } catch (Exception ignored) {
                // keep query params
            }
        }

        return new VlessConfig(
                userInfo,
                host,
                port,
                security,
                sni,
                fingerprint,
                publicKey,
                shortId,
                transport,
                path,
                xhttpMode,
                xPaddingBytes,
                scMaxEachPostBytes,
                remark
        );
    }

    private static String query(Uri uri, String key, String fallback) {
        String value = uri.getQueryParameter(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
