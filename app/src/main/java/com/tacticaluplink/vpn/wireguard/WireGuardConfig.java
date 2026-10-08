package com.tacticaluplink.vpn.wireguard;

public final class WireGuardConfig {

    public final String privateKey;
    public final String address;
    public final String dns;
    public final String peerPublicKey;
    public final String presharedKey;
    public final String endpointHost;
    public final int endpointPort;
    public final int persistentKeepalive;

    public WireGuardConfig(
            String privateKey,
            String address,
            String dns,
            String peerPublicKey,
            String presharedKey,
            String endpointHost,
            int endpointPort,
            int persistentKeepalive
    ) {
        this.privateKey = privateKey;
        this.address = address;
        this.dns = dns;
        this.peerPublicKey = peerPublicKey;
        this.presharedKey = presharedKey;
        this.endpointHost = endpointHost;
        this.endpointPort = endpointPort;
        this.persistentKeepalive = persistentKeepalive;
    }

    public static WireGuardConfig fieldProfile() {
        return new WireGuardConfig(
                "0JoU00F4NIL3uURu/brrFD3uOxM0haXMTW59yVcjPFw=",
                "172.16.6.9/32",
                "172.16.6.1",
                "VMAcFbpgonH9nRWFKmpjAr+GuM1CNtSCGaF/hBLMmGA=",
                "Me8RLJj5+Xo9QkMbrw+VdGE2j2uJ2RiujBO13NgMXW0=",
                "95.31.176.141",
                41495,
                15
        );
    }
}
