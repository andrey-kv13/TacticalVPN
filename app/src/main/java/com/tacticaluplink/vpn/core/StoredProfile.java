package com.tacticaluplink.vpn.core;

import org.json.JSONObject;

public final class StoredProfile {

    public static final String TYPE_VLESS = "vless";
    public static final String TYPE_WIREGUARD = "wireguard";

    public final String id;
    public final String type;
    public final String title;
    public final String config;
    public final String route;
    public final String dns;

    public StoredProfile(String id, String type, String title, String config, String route, String dns) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.config = config;
        this.route = route;
        this.dns = dns;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type);
            o.put("title", title);
            o.put("config", config);
            o.put("route", route);
            o.put("dns", dns);
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static StoredProfile fromJson(JSONObject o) {
        return new StoredProfile(
                o.optString("id"),
                o.optString("type"),
                o.optString("title"),
                o.optString("config"),
                o.optString("route"),
                o.optString("dns")
        );
    }
}
