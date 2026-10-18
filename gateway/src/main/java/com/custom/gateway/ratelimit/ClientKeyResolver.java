package com.custom.gateway.ratelimit;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class ClientKeyResolver {

    private static final String UNKNOWN = "unknown";

    private final boolean trustForwardedFor;

    public ClientKeyResolver(RateLimitProperties properties) {
        this.trustForwardedFor = properties.trustForwardedFor();
    }

    public String resolve(ServerRequest request) {
        String ip = null;
        if (trustForwardedFor) {
            String forwarded = request.headers().firstHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                ip = forwarded.split(",")[0].trim();
            }
        }
        if (ip == null || ip.isEmpty()) {
            // Deliberately not ServerRequest.remoteAddress(): it builds an InetSocketAddress
            // from getRemoteHost(), so a hostname gets DNS-resolved. That both costs a lookup
            // per request and buckets the caller under a different address than the service
            // sees, splitting one client across two limits.
            ip = request.servletRequest().getRemoteAddr();
        }
        String key = normalise(ip);
        // An empty key makes the gateway's rate limit filter answer 403, so never return one.
        return key.isEmpty() ? UNKNOWN : key;
    }

    /**
     * A single IPv6 customer gets a whole /64, so limiting a full address is free bypass.
     * IPv4 is returned unchanged.
     */
    static String normalise(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) {
                return ip;
            }
            StringBuilder prefix = new StringBuilder(20);
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) {
                    prefix.append(':');
                }
                prefix.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
            }
            return prefix.append("::/64").toString();
        }
        catch (UnknownHostException e) {
            return ip;
        }
    }
}
