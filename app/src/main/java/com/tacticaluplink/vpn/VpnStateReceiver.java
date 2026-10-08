package com.tacticaluplink.vpn;

public final class VpnStateReceiver {

    public static final String ACTION = "com.tacticaluplink.vpn.STATE";

    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_DISCONNECTED = "disconnected";
    public static final String STATE_ERROR = "error";

    private VpnStateReceiver() {
    }
}
