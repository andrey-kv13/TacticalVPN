package com.tacticaluplink.vpn;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.tacticaluplink.vpn.core.DiagnosticLog;
import com.tacticaluplink.vpn.core.StoredProfile;
import com.tacticaluplink.vpn.core.VpnController;

import org.json.JSONArray;

public final class WebAppInterface {

    public interface Host {
        void onProfileToggleRequested(String profileId, boolean enabled);

        String getActiveProfileId();

        boolean isVpnConnected();

        /** Системный выбор .conf WireGuard (SAF). */
        void onPickWireGuardConfRequested();
    }

    private final Host host;
    private final Context appContext;

    public WebAppInterface(Host host, Context context) {
        this.host = host;
        this.appContext = context.getApplicationContext();
        VpnController.get().init(appContext);
    }

    @JavascriptInterface
    public void setProfileEnabled(String profileId, boolean enabled) {
        if (profileId == null || profileId.isBlank()) {
            DiagnosticLog.w("UI", "setProfileEnabled: empty profileId");
            return;
        }
        if (VpnController.get().profiles().get(profileId) == null) {
            DiagnosticLog.e("UI", "Unknown profile id: " + profileId);
            return;
        }
        host.onProfileToggleRequested(profileId, enabled);
    }

    @JavascriptInterface
    public void setUplinkEnabled(boolean enabled) {
        host.onProfileToggleRequested(VpnController.get().getActiveProfileId(), enabled);
    }

    @JavascriptInterface
    public String getActiveProfileId() {
        return host.getActiveProfileId();
    }

    @JavascriptInterface
    public boolean isUplinkEnabled() {
        return host.isVpnConnected();
    }

    @JavascriptInterface
    public boolean isProfileEnabled(String profileId) {
        return host.isVpnConnected() && profileId != null && profileId.equals(host.getActiveProfileId());
    }

    @JavascriptInterface
    public String listProfilesJson() {
        JSONArray arr = new JSONArray();
        for (StoredProfile p : VpnController.get().profiles().list()) {
            arr.put(p.toJson());
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void pickWireGuardConfFile() {
        host.onPickWireGuardConfRequested();
    }

    @JavascriptInterface
    public String addProfile(String type, String title, String configText) {
        try {
            String id = VpnController.get().profiles().add(type, title, configText);
            DiagnosticLog.i("UI", "Profile added from HUD: " + id);
            return id;
        } catch (Exception e) {
            DiagnosticLog.e("UI", "addProfile failed", e);
            return "ERROR:" + e.getMessage();
        }
    }

    @JavascriptInterface
    public String deleteProfile(String profileId) {
        try {
            if (profileId == null || profileId.isBlank()) {
                return "ERROR:empty id";
            }
            if (host.isVpnConnected() && profileId.equals(host.getActiveProfileId())) {
                return "ERROR:disconnect first";
            }
            boolean ok = VpnController.get().profiles().delete(profileId);
            return ok ? "OK" : "ERROR:not found";
        } catch (Exception e) {
            DiagnosticLog.e("UI", "deleteProfile failed", e);
            return "ERROR:" + e.getMessage();
        }
    }

    @JavascriptInterface
    public String getDiagnosticLogs(int maxLines) {
        return DiagnosticLog.exportJson(maxLines).toString();
    }

    @JavascriptInterface
    public void clearDiagnosticLogs() {
        DiagnosticLog.clear();
        DiagnosticLog.i("UI", "Diagnostic log cleared from HUD");
    }
}
