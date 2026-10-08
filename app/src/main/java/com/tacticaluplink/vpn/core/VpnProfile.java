package com.tacticaluplink.vpn.core;

import com.tacticaluplink.vpn.vless.VlessConfig;
import com.tacticaluplink.vpn.vless.XrayConfigBuilder;
import com.tacticaluplink.vpn.wireguard.WireGuardConfig;

public final class VpnProfile {

    public static final String NX62 = "nx62";
    public static final String TACTICAL = "tactical";
    public static final String WIREGUARD = "wireguard";

    private VpnProfile() {
    }

    public static boolean isKnown(String profileId) {
        return NX62.equals(profileId) || TACTICAL.equals(profileId) || WIREGUARD.equals(profileId);
    }

    public static String normalize(String profileId) {
        if (isKnown(profileId)) {
            return profileId;
        }
        return NX62;
    }

    public static String buildXrayJson(String profileId) {
        String id = normalize(profileId);
        switch (id) {
            case TACTICAL:
                return XrayConfigBuilder.build(VlessConfig.tacticalProfile());
            case WIREGUARD:
                return XrayConfigBuilder.build(WireGuardConfig.fieldProfile());
            case NX62:
            default:
                return XrayConfigBuilder.build(VlessConfig.defaultProfile());
        }
    }

    public static String hudTitle(String profileId) {
        switch (normalize(profileId)) {
            case TACTICAL:
                return "КАНАЛ TACTICAL";
            case WIREGUARD:
                return "ЩИТ WIREGUARD";
            case NX62:
            default:
                return "АПЛИНК NX62";
        }
    }
}
