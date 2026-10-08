package com.tacticaluplink.vpn;

import android.app.Application;
import android.util.Log;

import com.tacticaluplink.vpn.core.DiagnosticLog;
import com.tacticaluplink.vpn.core.VpnController;

import java.io.File;

public final class TacticalApplication extends Application {

    private static final String TAG = "TacticalUplink";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.system.Os.setenv("GODEBUG", "runtime_epoll_pwait2=0", true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set GODEBUG", e);
        }
        VpnController.get().init(this);
        DiagnosticLog.bindSpillFile(new File(getFilesDir(), "vpn_diagnostic.log"));
        DiagnosticLog.i("App", "Tactical Uplink started uid=" + android.os.Process.myUid());
    }
}
