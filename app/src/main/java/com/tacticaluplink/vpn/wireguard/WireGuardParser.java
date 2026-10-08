package com.tacticaluplink.vpn.wireguard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WireGuardParser {

    private WireGuardParser() {
    }

    public static WireGuardConfig parse(String confText) {
        if (confText == null || confText.isBlank()) {
            throw new IllegalArgumentException("WireGuard config is empty");
        }
        String text = confText.replace("\r\n", "\n").trim();

        String privateKey = sectionValue(text, "Interface", "PrivateKey");
        String address = sectionValue(text, "Interface", "Address");
        String dns = sectionValue(text, "Interface", "DNS");
        if (dns != null && !dns.isBlank()) {
            dns = dns.split(",")[0].trim();
        } else {
            dns = "";
        }

        String peerPublicKey = sectionValue(text, "Peer", "PublicKey");
        String psk = sectionValue(text, "Peer", "PresharedKey");
        String endpoint = sectionValue(text, "Peer", "Endpoint");
        String keepaliveStr = sectionValue(text, "Peer", "PersistentKeepalive");

        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalArgumentException("Missing Interface PrivateKey");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Missing Interface Address");
        }
        if (peerPublicKey == null || peerPublicKey.isBlank()) {
            throw new IllegalArgumentException("Missing Peer PublicKey");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Missing Peer Endpoint");
        }

        Endpoint ep = parseEndpoint(endpoint);
        int keepalive = 25;
        if (keepaliveStr != null && !keepaliveStr.isBlank()) {
            try {
                keepalive = Integer.parseInt(keepaliveStr.trim());
            } catch (NumberFormatException ignored) {
                keepalive = 25;
            }
        }

        if (psk == null) {
            psk = "";
        }

        return new WireGuardConfig(
                privateKey.trim(),
                address.trim(),
                dns,
                peerPublicKey.trim(),
                psk.trim(),
                ep.host,
                ep.port,
                keepalive
        );
    }

    private static Endpoint parseEndpoint(String endpoint) {
        String trimmed = endpoint.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]:")) {
            int end = trimmed.indexOf("]:");
            String host = trimmed.substring(1, end);
            int port = Integer.parseInt(trimmed.substring(end + 2));
            return new Endpoint(host, port);
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Invalid Endpoint: " + endpoint);
        }
        return new Endpoint(
                trimmed.substring(0, colon),
                Integer.parseInt(trimmed.substring(colon + 1))
        );
    }

    private static String sectionValue(String text, String section, String key) {
        Pattern p = Pattern.compile(
                "\\[" + section + "\\][\\s\\S]*?(?=\\[|$)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        String block = m.group();
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = trimmed.substring(0, eq).trim();
            if (k.equalsIgnoreCase(key)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
