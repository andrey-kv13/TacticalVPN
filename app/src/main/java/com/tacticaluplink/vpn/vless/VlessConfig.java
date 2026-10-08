package com.tacticaluplink.vpn.vless;

import java.util.Objects;

public final class VlessConfig {

    public final String uuid;
    public final String host;
    public final int port;
    public final String security;
    public final String sni;
    public final String fingerprint;
    public final String publicKey;
    public final String shortId;
    public final String transport;
    public final String path;
    public final String xhttpMode;
    public final String xPaddingBytes;
    public final String scMaxEachPostBytes;
    public final String remark;

    public VlessConfig(
            String uuid,
            String host,
            int port,
            String security,
            String sni,
            String fingerprint,
            String publicKey,
            String shortId,
            String transport,
            String path,
            String xhttpMode,
            String xPaddingBytes,
            String scMaxEachPostBytes,
            String remark
    ) {
        this.uuid = uuid;
        this.host = host;
        this.port = port;
        this.security = security;
        this.sni = sni;
        this.fingerprint = fingerprint;
        this.publicKey = publicKey;
        this.shortId = shortId;
        this.transport = transport;
        this.path = path;
        this.xhttpMode = xhttpMode;
        this.xPaddingBytes = xPaddingBytes;
        this.scMaxEachPostBytes = scMaxEachPostBytes;
        this.remark = remark;
    }

    public static VlessConfig defaultProfile() {
        return Objects.requireNonNull(
                VlessParser.parse(defaultShareUrl()),
                "default VLESS profile"
        );
    }

    public static String defaultShareUrl() {
        return "vless://a1ce43ae-df55-47cb-9223-d189e1064ed9@nx62.duckdns.org:443"
                + "?encryption=none"
                + "&extra=%7B%22mode%22%3A%22stream-one%22%2C%22scMaxEachPostBytes%22%3A%221000000%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D"
                + "&fp=safari"
                + "&mode=stream-one"
                + "&path=%2F"
                + "&pbk=kB3qPWQ-aVZ1GpZ9M6N7AAuiLNrXfEM2btjznQ4TpRU"
                + "&security=reality"
                + "&sid=a1c2b3d4"
                + "&sni=www.samsung.com"
                + "&type=xhttp"
                + "&x_padding_bytes=100-1000"
                + "#xhttpnew";
    }

    public static String tacticalShareUrl() {
        return "vless://89e76037-c8e7-47e2-8d69-dccdca2f75ff@95.31.176.141:2053"
                + "?encryption=none"
                + "&extra=%7B%22mode%22%3A%22stream-one%22%2C%22scMaxEachPostBytes%22%3A%221000000%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D"
                + "&fp=safari"
                + "&mode=stream-one"
                + "&path=%2F"
                + "&pbk=GClSKH4OqSn3z3qxtGt1k0aSYvYFt6hEQel6hRjEYiw"
                + "&security=reality"
                + "&sid=a1c2b3d4"
                + "&sni=www.asus.com"
                + "&type=xhttp"
                + "&x_padding_bytes=100-1000"
                + "#TacticalChannel";
    }

    public static VlessConfig tacticalProfile() {
        return Objects.requireNonNull(
                VlessParser.parse(tacticalShareUrl()),
                "tactical VLESS profile"
        );
    }
}
