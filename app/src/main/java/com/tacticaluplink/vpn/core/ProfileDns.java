package com.tacticaluplink.vpn.core;

import com.tacticaluplink.vpn.vless.XrayConfigBuilder;
import com.tacticaluplink.vpn.wireguard.WireGuardConfig;
import com.tacticaluplink.vpn.wireguard.WireGuardParser;

/**
 * TUN / Xray DNS по полю профиля, без привязки к id nx62/tactical (кроме podkop-констант).
 */
public final class ProfileDns {

    public static final String DNS_HOME_ROUTER = "192.168.2.1";
    /** DNS роутера с podkop FakeIP (ваша инфраструктура). */
    public static final String DNS_PODKOP_REMOTE = "172.16.6.1";
    public static final String DNS_PUBLIC = "1.1.1.1";

    private ProfileDns() {
    }

    public static boolean isPodkopRemote(String resolver) {
        return DNS_PODKOP_REMOTE.equals(normalize(resolver));
    }

    public static boolean isHomeRouter(String resolver) {
        return DNS_HOME_ROUTER.equals(normalize(resolver));
    }

    public static ProfileStore.DnsPair tunForProfile(StoredProfile profile) {
        if (profile == null) {
            return new ProfileStore.DnsPair(DNS_PUBLIC, "");
        }
        if (StoredProfile.TYPE_WIREGUARD.equals(profile.type)) {
            String dns = wireGuardDns(profile);
            if (isPodkopRemote(dns)) {
                return new ProfileStore.DnsPair(dns, "");
            }
            return new ProfileStore.DnsPair(orPublic(dns), "");
        }
        String resolver = normalize(profile.dns);
        if (resolver.isEmpty()) {
            resolver = DNS_PUBLIC;
        }
        if (isHomeRouter(resolver)) {
            return new ProfileStore.DnsPair(DNS_HOME_ROUTER, "");
        }
        if (isPodkopRemote(resolver)) {
            return new ProfileStore.DnsPair(XrayConfigBuilder.TUN_DNS_FAKEIP_GATEWAY, DNS_PODKOP_REMOTE);
        }
        return new ProfileStore.DnsPair(resolver, "");
    }

    /** DNS для нового VLESS: домашний NX62 / podkop VPS / иначе публичный (как v2rayNG). */
    public static String defaultVlessDns(String host) {
        if (host == null || host.isBlank()) {
            return DNS_PUBLIC;
        }
        String h = host.trim().toLowerCase();
        if (h.contains("duckdns.org") || h.startsWith("192.168.") || h.contains("nx62")) {
            return DNS_HOME_ROUTER;
        }
        if ("95.31.176.141".equals(h)) {
            return DNS_PODKOP_REMOTE;
        }
        return DNS_PUBLIC;
    }

    private static String wireGuardDns(StoredProfile profile) {
        try {
            WireGuardConfig wg = WireGuardParser.parse(profile.config);
            return normalize(wg.dns);
        } catch (Exception e) {
            return normalize(profile.dns);
        }
    }

    private static String orPublic(String dns) {
        String n = normalize(dns);
        return n.isEmpty() ? DNS_PUBLIC : n;
    }

    private static String normalize(String dns) {
        return dns != null ? dns.trim() : "";
    }
}
