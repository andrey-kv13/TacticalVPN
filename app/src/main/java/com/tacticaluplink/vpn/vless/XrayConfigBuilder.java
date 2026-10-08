package com.tacticaluplink.vpn.vless;

import com.tacticaluplink.vpn.core.ProfileDns;
import com.tacticaluplink.vpn.wireguard.WireGuardConfig;

import org.json.JSONArray;
import org.json.JSONObject;

public final class XrayConfigBuilder {

    private static final String DNS_HOME_ROUTER = ProfileDns.DNS_HOME_ROUTER;
    private static final String DNS_REMOTE_ROUTER = ProfileDns.DNS_PODKOP_REMOTE;
    private static final String DNS_PUBLIC_TCP = ProfileDns.DNS_PUBLIC;
    /** TUN DNS для профилей с podkop FakeIP (Tactical / WireGuard), не для nx62. */
    public static final String TUN_DNS_FAKEIP_GATEWAY = "198.18.0.2";
    private static final String FAKEIP_POOL = "198.18.0.0/15";

    private XrayConfigBuilder() {
    }

    public static String build(VlessConfig config) {
        return build(config, DNS_PUBLIC_TCP);
    }

    /** VLESS: nx62 — TCP DNS домашнего роутера; Tactical — FakeIP + TCP 172.16.6.1 (podkop). */
    public static String build(VlessConfig config, String primaryDns) {
        try {
            String resolver = primaryDns != null && !primaryDns.isBlank()
                    ? primaryDns.trim()
                    : DNS_PUBLIC_TCP;
            boolean remotePodkop = ProfileDns.isPodkopRemote(resolver);
            boolean exclusiveRouterDns = ProfileDns.isHomeRouter(resolver) || remotePodkop
                    || (!DNS_PUBLIC_TCP.equals(resolver) && !resolver.isEmpty());
            JSONObject dnsBlock = remotePodkop
                    ? buildPodkopFakeIpDns(resolver)
                    : buildTunDns(resolver, exclusiveRouterDns);
            return buildCore(
                    buildProxyOutbound(config),
                    dnsBlock,
                    "debug",
                    config.host,
                    config.port,
                    false,
                    remotePodkop,
                    true,
                    true
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Xray VLESS config", e);
        }
    }

    /**
     * WireGuard: как официальный клиент — DNS роутера на TUN, без FakeDNS и без sniffing
     * (sniffing заставляет outbound WG резолвить домены через системный 1.1.1.1 и ломает трафик).
     */
    public static String build(WireGuardConfig config) {
        try {
            String resolver = config.dns != null && !config.dns.isBlank()
                    ? config.dns.trim()
                    : DNS_PUBLIC_TCP;
            boolean podkop = ProfileDns.isPodkopRemote(resolver);
            boolean exclusiveRouterDns = podkop
                    || ProfileDns.isHomeRouter(resolver)
                    || (!DNS_PUBLIC_TCP.equals(resolver) && !resolver.isEmpty());
            return buildCore(
                    buildWireGuardOutbound(config),
                    buildTunDns(resolver, exclusiveRouterDns),
                    "info",
                    config.endpointHost,
                    config.endpointPort,
                    true,
                    podkop,
                    false,
                    false
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Xray WireGuard config", e);
        }
    }

    /**
     * DNS для модуля Xray (TCP). Системные запросы на IP из TUN идут отдельно через [proxy], как у NX62 → 192.168.2.1.
     */
    /** exclusive=true: только DNS роутера (NX62 или удалённый), без 1.1.1.1 мимо podkop. */
    private static JSONObject buildTunDns(String resolver, boolean exclusive) throws Exception {
        JSONArray servers = new JSONArray();
        JSONObject primary = new JSONObject();
        primary.put("address", "tcp://" + resolver);
        primary.put("skipFallback", exclusive);
        servers.put(primary);
        if (!exclusive) {
            JSONObject fallback = new JSONObject();
            fallback.put("address", "tcp://" + DNS_PUBLIC_TCP);
            servers.put(fallback);
        }
        JSONObject dns = new JSONObject()
                .put("servers", servers)
                .put("queryStrategy", "UseIPv4");
        if (exclusive) {
            dns.put("disableFallback", true);
        }
        return dns;
    }

    /** FakeDNS + TCP DNS роутера с podkop (только Tactical VLESS). TUN DNS = {@link #TUN_DNS_FAKEIP_GATEWAY}. */
    private static JSONObject buildPodkopFakeIpDns(String routerResolver) throws Exception {
        JSONArray servers = new JSONArray();
        servers.put("fakedns");
        JSONObject upstream = new JSONObject();
        upstream.put("address", "tcp://" + routerResolver);
        upstream.put("skipFallback", true);
        servers.put(upstream);
        return new JSONObject()
                .put("servers", servers)
                .put("queryStrategy", "UseIPv4")
                .put("fakeDns", new JSONObject().put("ipPool", FAKEIP_POOL));
    }

    private static String buildCore(
            JSONObject proxyOutbound,
            JSONObject dnsBlock,
            String logLevel,
            String bypassHost,
            int bypassPort,
            boolean wireGuardMode,
            boolean routeFakeIpPoolToProxy,
            boolean enableTunSniffing,
            boolean includeSocksInbound
    ) {
        try {
            JSONObject root = new JSONObject();
            root.put("log", new JSONObject().put("loglevel", logLevel));

            JSONArray inbounds = new JSONArray();
            inbounds.put(buildAndroidTunInbound(enableTunSniffing));
            if (includeSocksInbound) {
                inbounds.put(buildSocksInbound());
            }
            root.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            outbounds.put(proxyOutbound);
            outbounds.put(new JSONObject()
                    .put("tag", "direct")
                    .put("protocol", "freedom")
                    .put("settings", new JSONObject().put("domainStrategy", "UseIPv4")));
            outbounds.put(new JSONObject()
                    .put("tag", "block")
                    .put("protocol", "blackhole"));
            root.put("outbounds", outbounds);

            JSONArray rules = new JSONArray();
            appendBypassRules(rules, bypassHost, bypassPort);
            if (routeFakeIpPoolToProxy) {
                rules.put(new JSONObject()
                        .put("type", "field")
                        .put("ip", new JSONArray().put(FAKEIP_POOL))
                        .put("outboundTag", "proxy"));
            }
            rules.put(new JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "proxy"));
            String domainStrategy = wireGuardMode ? "AsIs" : "IPIfNonMatch";
            root.put("routing", new JSONObject()
                    .put("domainStrategy", domainStrategy)
                    .put("rules", rules));

            root.put("dns", dnsBlock);

            return root.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Xray config", e);
        }
    }

    /** Без direct до сервера прокси весь трафик зацикливается в TUN — «ничего не открывается». */
    private static void appendBypassRules(JSONArray rules, String bypassHost, int bypassPort) throws Exception {
        if (bypassHost == null || bypassHost.isBlank()) {
            return;
        }
        String host = bypassHost.trim();
        if (looksLikeIpv4(host)) {
            rules.put(new JSONObject()
                    .put("type", "field")
                    .put("outboundTag", "direct")
                    .put("ip", new JSONArray().put(host)));
        } else {
            JSONArray domains = new JSONArray();
            domains.put("full:" + host);
            domains.put("domain:" + host);
            rules.put(new JSONObject()
                    .put("type", "field")
                    .put("outboundTag", "direct")
                    .put("domain", domains));
        }
    }

    private static boolean looksLikeIpv4(String host) {
        if (host == null) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject buildWireGuardOutbound(WireGuardConfig config) throws Exception {
        JSONObject peer = new JSONObject();
        peer.put("publicKey", config.peerPublicKey);
        peer.put("preSharedKey", config.presharedKey);
        peer.put("endpoint", config.endpointHost + ":" + config.endpointPort);
        peer.put("keepAlive", config.persistentKeepalive);
        peer.put("allowedIPs", new JSONArray().put("0.0.0.0/0").put("::/0"));

        JSONObject settings = new JSONObject();
        settings.put("secretKey", config.privateKey);
        settings.put("address", new JSONArray().put(config.address));
        settings.put("peers", new JSONArray().put(peer));
        settings.put("mtu", 1420);

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "wireguard");
        outbound.put("settings", settings);
        return outbound;
    }

    private static JSONObject buildAndroidTunInbound(boolean enableSniffing) throws Exception {
        JSONObject inbound = new JSONObject();
        inbound.put("tag", "tun-in");
        inbound.put("port", 0);
        inbound.put("protocol", "tun");
        JSONObject tunSettings = new JSONObject();
        tunSettings.put("name", "tun0");
        tunSettings.put("mtu", wireGuardTunMtu(enableSniffing));
        inbound.put("settings", tunSettings);
        if (enableSniffing) {
            inbound.put("sniffing", new JSONObject()
                    .put("enabled", true)
                    .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));
        }
        return inbound;
    }

    /** WG outbound MTU 1420; TUN 1500 + двойная обёртка даёт blackhole на QUIC/YouTube. */
    private static int wireGuardTunMtu(boolean enableSniffing) {
        return enableSniffing ? 1500 : 1420;
    }

    private static JSONObject buildSocksInbound() throws Exception {
        JSONObject inbound = new JSONObject();
        inbound.put("tag", "socks-in");
        inbound.put("port", 10808);
        inbound.put("listen", "127.0.0.1");
        inbound.put("protocol", "socks");
        inbound.put("settings", new JSONObject()
                .put("auth", "noauth")
                .put("udp", true));
        inbound.put("sniffing", new JSONObject()
                .put("enabled", true)
                .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));
        return inbound;
    }

    private static JSONObject buildProxyOutbound(VlessConfig config) throws Exception {
        JSONObject user = new JSONObject();
        user.put("id", config.uuid);
        user.put("encryption", "none");
        user.put("level", 8);

        JSONObject vnextEntry = new JSONObject();
        vnextEntry.put("address", config.host);
        vnextEntry.put("port", config.port);
        vnextEntry.put("users", new JSONArray().put(user));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "vless");
        outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(vnextEntry)));
        outbound.put("streamSettings", buildStreamSettings(config));
        outbound.put("mux", new JSONObject().put("enabled", false));
        outbound.put("packetEncoding", "xudp");
        return outbound;
    }

    private static JSONObject buildStreamSettings(VlessConfig config) throws Exception {
        JSONObject stream = new JSONObject();
        String transport = config.transport != null && !config.transport.isBlank()
                ? config.transport.trim().toLowerCase()
                : "tcp";
        stream.put("network", transport);

        String sec = config.security != null ? config.security.trim().toLowerCase() : "none";
        if ("none".equals(sec) || sec.isEmpty()) {
            stream.put("security", "none");
        } else {
            stream.put("security", sec);
        }

        if ("tls".equals(sec)) {
            JSONObject tls = new JSONObject();
            tls.put("serverName", config.sni);
            if (config.fingerprint != null && !config.fingerprint.isBlank()) {
                tls.put("fingerprint", config.fingerprint);
            }
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(sec)) {
            JSONObject reality = new JSONObject();
            reality.put("serverName", config.sni);
            reality.put("fingerprint", config.fingerprint);
            reality.put("publicKey", config.publicKey);
            reality.put("shortId", config.shortId);
            reality.put("spiderX", "");
            stream.put("realitySettings", reality);
        }

        appendTransportSettings(stream, transport, config);
        return stream;
    }

    private static void appendTransportSettings(JSONObject stream, String transport, VlessConfig config)
            throws Exception {
        if ("ws".equals(transport)) {
            JSONObject ws = new JSONObject();
            ws.put("path", config.path != null && !config.path.isBlank() ? config.path : "/");
            if (config.sni != null && !config.sni.isBlank()) {
                ws.put("headers", new JSONObject().put("Host", config.sni));
            }
            stream.put("wsSettings", ws);
            return;
        }
        if ("grpc".equals(transport)) {
            JSONObject grpc = new JSONObject();
            grpc.put("serviceName", config.path != null && !config.path.isBlank() ? config.path : "");
            stream.put("grpcSettings", grpc);
            return;
        }
        if ("xhttp".equalsIgnoreCase(transport)) {
            JSONObject extra = new JSONObject();
            extra.put("mode", config.xhttpMode);
            extra.put("scMaxEachPostBytes", Long.parseLong(config.scMaxEachPostBytes));
            extra.put("xPaddingBytes", config.xPaddingBytes);

            JSONObject xhttp = new JSONObject();
            xhttp.put("path", config.path != null && !config.path.isBlank() ? config.path : "/");
            xhttp.put("mode", config.xhttpMode);
            xhttp.put("extra", extra);
            stream.put("xhttpSettings", xhttp);
        }
    }
}
