package com.tacticaluplink.vpn.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.tacticaluplink.vpn.vless.VlessConfig;
import com.tacticaluplink.vpn.vless.VlessParser;
import com.tacticaluplink.vpn.vless.XrayConfigBuilder;
import com.tacticaluplink.vpn.wireguard.WireGuardConfig;
import com.tacticaluplink.vpn.wireguard.WireGuardParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ProfileStore {

    private static final String PREFS = "tactical_uplink_profiles";
    private static final String KEY_LIST = "profiles_json";
    private static final String KEY_SEEDED = "defaults_seeded_v2";
    private static final String KEY_DNS_MIGRATED_V3 = "dns_migrated_v3";
    private static final String KEY_DNS_MIGRATED_V4 = "dns_migrated_v4";
    private static final String KEY_DNS_REMOTE_ROUTER_V5 = "dns_remote_router_v5";

    private static final String DNS_HOME_ROUTER = ProfileDns.DNS_HOME_ROUTER;
    private static final String DNS_REMOTE_ROUTER = ProfileDns.DNS_PODKOP_REMOTE;
    private static final String DNS_VPS_INTERNAL = DNS_REMOTE_ROUTER;
    private static final String DNS_PUBLIC_TCP = ProfileDns.DNS_PUBLIC;
    private final SharedPreferences prefs;

    public ProfileStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        seedDefaultsIfNeeded();
        ensureBuiltInProfiles();
        migrateDnsForRemoteProfiles();
        migrateDnsVpsInternal();
        enforceRemoteRouterDnsOnBuiltIns();
    }

    private void seedDefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED, false)) {
            return;
        }
        JSONArray arr = new JSONArray();
        try {
            arr.put(buildDefaultNx62().toJson());
            arr.put(buildDefaultTactical().toJson());
            arr.put(buildDefaultWireGuard().toJson());
        } catch (Exception e) {
            DiagnosticLog.e("ProfileStore", "Failed to seed defaults", e);
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).putBoolean(KEY_SEEDED, true).apply();
        DiagnosticLog.i("ProfileStore", "Default profiles seeded: " + arr.length());
    }

    /** Keeps nx62 / tactical / wireguard available after updates. */
    private synchronized void ensureBuiltInProfiles() {
        JSONArray arr = readArray();
        boolean hasNx = false;
        boolean hasTac = false;
        boolean hasWg = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id");
            if ("nx62".equals(id)) {
                hasNx = true;
            } else if ("tactical".equals(id)) {
                hasTac = true;
            } else if ("wireguard".equals(id)) {
                hasWg = true;
            }
        }
        try {
            if (!hasNx) {
                arr.put(buildDefaultNx62().toJson());
            }
            if (!hasTac) {
                arr.put(buildDefaultTactical().toJson());
            }
            if (!hasWg) {
                arr.put(buildDefaultWireGuard().toJson());
            }
            if (!hasNx || !hasTac || !hasWg) {
                saveArray(arr);
                DiagnosticLog.i("ProfileStore", "Restored built-in profiles");
            }
        } catch (Exception e) {
            DiagnosticLog.e("ProfileStore", "ensureBuiltInProfiles failed", e);
        }
    }

    public synchronized List<StoredProfile> list() {
        List<StoredProfile> out = new ArrayList<>();
        JSONArray arr = readArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                out.add(StoredProfile.fromJson(o));
            }
        }
        return out;
    }

    public synchronized StoredProfile get(String id) {
        if (id == null) {
            return null;
        }
        for (StoredProfile p : list()) {
            if (id.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    public synchronized String add(String type, String title, String configText) {
        String normalizedType = type != null ? type.trim().toLowerCase() : "";
        if (!StoredProfile.TYPE_VLESS.equals(normalizedType)
                && !StoredProfile.TYPE_WIREGUARD.equals(normalizedType)) {
            throw new IllegalArgumentException("type must be vless or wireguard");
        }
        String config = configText != null ? configText.trim() : "";
        if (config.isEmpty()) {
            throw new IllegalArgumentException("config is empty");
        }
        StoredProfile parsed = buildFromInput(
                UUID.randomUUID().toString(),
                normalizedType,
                title,
                config
        );
        JSONArray arr = readArray();
        arr.put(parsed.toJson());
        saveArray(arr);
        DiagnosticLog.i("ProfileStore", "Added profile " + parsed.id + " " + parsed.title);
        return parsed.id;
    }

    public synchronized boolean delete(String id) {
        if ("nx62".equals(id) || "tactical".equals(id) || "wireguard".equals(id)) {
            throw new IllegalArgumentException("built-in profile cannot be deleted");
        }
        JSONArray arr = readArray();
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (id.equals(o.optString("id"))) {
                removed = true;
                continue;
            }
            next.put(o);
        }
        if (removed) {
            saveArray(next);
            DiagnosticLog.w("ProfileStore", "Deleted profile " + id);
        }
        return removed;
    }

    public String buildXrayJson(String profileId) {
        StoredProfile profile = get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown profile: " + profileId);
        }
        if (StoredProfile.TYPE_VLESS.equals(profile.type)) {
            VlessConfig vless = VlessParser.parse(profile.config);
            DiagnosticLog.i("Xray", "Build VLESS " + vless.host + ":" + vless.port + " dns=" + profile.dns);
            return XrayConfigBuilder.build(vless, profile.dns);
        }
        WireGuardConfig wg = WireGuardParser.parse(profile.config);
        DiagnosticLog.i("Xray", "Build WG endpoint " + wg.endpointHost + ":" + wg.endpointPort + " dns=" + wg.dns
                + " mode=ip-only");
        return XrayConfigBuilder.build(wg);
    }

    public DnsPair dnsForProfile(String profileId) {
        return ProfileDns.tunForProfile(get(profileId));
    }

    /** Раньше всем VLESS ставили 192.168.2.1 — для tactical/WG это ломало YouTube и сайты. */
    private synchronized void migrateDnsForRemoteProfiles() {
        if (prefs.getBoolean(KEY_DNS_MIGRATED_V3, false)) {
            return;
        }
        JSONArray arr = readArray();
        boolean changed = false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id");
            if ("tactical".equals(id) && DNS_HOME_ROUTER.equals(o.optString("dns"))) {
                try {
                    o.put("dns", DNS_PUBLIC_TCP);
                    changed = true;
                    DiagnosticLog.i("ProfileStore", "migrate dns tactical -> " + DNS_PUBLIC_TCP);
                } catch (JSONException ignored) {
                }
            }
            next.put(o);
        }
        if (changed) {
            saveArray(next);
        }
        prefs.edit().putBoolean(KEY_DNS_MIGRATED_V3, true).apply();
    }

    /** Tactical на VPS: резолвер 172.16.6.1 (как у WireGuard), не 1.1.1.1 / домашний роутер. */
    private synchronized void migrateDnsVpsInternal() {
        if (prefs.getBoolean(KEY_DNS_MIGRATED_V4, false)) {
            return;
        }
        JSONArray arr = readArray();
        boolean changed = false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id");
            if ("tactical".equals(id)) {
                String dns = o.optString("dns");
                if (DNS_HOME_ROUTER.equals(dns) || DNS_PUBLIC_TCP.equals(dns)) {
                    try {
                        o.put("dns", DNS_VPS_INTERNAL);
                        changed = true;
                        DiagnosticLog.i("ProfileStore", "migrate dns tactical -> " + DNS_VPS_INTERNAL);
                    } catch (JSONException ignored) {
                    }
                }
            }
            next.put(o);
        }
        if (changed) {
            saveArray(next);
        }
        prefs.edit().putBoolean(KEY_DNS_MIGRATED_V4, true).apply();
    }

    /** tactical / wireguard — только DNS удалённого роутера, никогда 192.168.2.1 (NX62). */
    private synchronized void enforceRemoteRouterDnsOnBuiltIns() {
        JSONArray arr = readArray();
        boolean changed = false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id");
            if ("tactical".equals(id) || "wireguard".equals(id)) {
                String dns = o.optString("dns");
                if (!DNS_REMOTE_ROUTER.equals(dns)) {
                    try {
                        o.put("dns", DNS_REMOTE_ROUTER);
                        changed = true;
                        DiagnosticLog.i("ProfileStore", id + ": dns -> " + DNS_REMOTE_ROUTER + " (remote router)");
                    } catch (JSONException ignored) {
                    }
                }
            }
            next.put(o);
        }
        if (changed) {
            saveArray(next);
        }
        if (!prefs.getBoolean(KEY_DNS_REMOTE_ROUTER_V5, false)) {
            prefs.edit().putBoolean(KEY_DNS_REMOTE_ROUTER_V5, true).apply();
        }
    }

    private StoredProfile buildFromInput(String id, String type, String title, String config) {
        if (StoredProfile.TYPE_VLESS.equals(type)) {
            VlessConfig vless = VlessParser.parse(config);
            String displayTitle = title != null && !title.isBlank() ? title.trim() : vless.remark;
            String route = vless.host + ":" + vless.port;
            String dns = vlessDnsForHost(vless.host);
            return new StoredProfile(id, type, displayTitle, config, route, dns);
        }
        WireGuardConfig wg = WireGuardParser.parse(config);
        String displayTitle = title != null && !title.isBlank() ? title.trim() : "WireGuard";
        return new StoredProfile(
                id,
                type,
                displayTitle,
                config,
                wg.endpointHost + ":" + wg.endpointPort,
                wg.dns
        );
    }

    private static String vlessDnsForHost(String host) {
        return ProfileDns.defaultVlessDns(host);
    }

    private StoredProfile buildDefaultNx62() {
        return buildFromInput(
                "nx62",
                StoredProfile.TYPE_VLESS,
                "АПЛИНК NX62",
                VlessConfig.defaultShareUrl()
        );
    }

    private StoredProfile buildDefaultTactical() {
        return buildFromInput(
                "tactical",
                StoredProfile.TYPE_VLESS,
                "КАНАЛ TACTICAL",
                VlessConfig.tacticalShareUrl()
        );
    }

    private StoredProfile buildDefaultWireGuard() {
        WireGuardConfig wg = WireGuardConfig.fieldProfile();
        String conf = "[Interface]\n"
                + "PrivateKey = " + wg.privateKey + "\n"
                + "Address = " + wg.address + "\n"
                + "DNS = " + wg.dns + "\n\n"
                + "[Peer]\n"
                + "PublicKey = " + wg.peerPublicKey + "\n"
                + "PresharedKey = " + wg.presharedKey + "\n"
                + "Endpoint = " + wg.endpointHost + ":" + wg.endpointPort + "\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "PersistentKeepalive = " + wg.persistentKeepalive + "\n";
        return buildFromInput("wireguard", StoredProfile.TYPE_WIREGUARD, "ЩИТ WIREGUARD", conf);
    }

    private JSONArray readArray() {
        String raw = prefs.getString(KEY_LIST, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            DiagnosticLog.e("ProfileStore", "Corrupt profile list, resetting", e);
            return new JSONArray();
        }
    }

    private void saveArray(JSONArray arr) {
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static final class DnsPair {
        public final String primary;
        public final String secondary;

        public DnsPair(String primary, String secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }
    }
}
