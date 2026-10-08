package com.tacticaluplink.vpn.core;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

public final class VpnController {

    private static final VpnController INSTANCE = new VpnController();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String activeProfileId = "nx62";
    private volatile ProfileStore profileStore;

    private VpnController() {
    }

    public static VpnController get() {
        return INSTANCE;
    }

    public void init(Context context) {
        if (profileStore == null) {
            profileStore = new ProfileStore(context.getApplicationContext());
            if (profileStore.get(activeProfileId) == null) {
                var list = profileStore.list();
                if (!list.isEmpty()) {
                    activeProfileId = list.get(0).id;
                }
            }
        }
    }

    public ProfileStore profiles() {
        if (profileStore == null) {
            throw new IllegalStateException("VpnController.init() was not called");
        }
        return profileStore;
    }

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public void setActiveProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        activeProfileId = profileId;
    }

    public String buildActiveXrayJson() {
        return profiles().buildXrayJson(activeProfileId);
    }

    public ProfileStore.DnsPair dnsForActiveProfile() {
        return profiles().dnsForProfile(activeProfileId);
    }

    public void setConnected(boolean value) {
        connected.set(value);
    }

    public boolean isConnected() {
        return connected.get();
    }
}
