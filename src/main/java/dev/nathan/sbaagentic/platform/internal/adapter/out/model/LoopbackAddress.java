package dev.nathan.sbaagentic.platform.internal.adapter.out.model;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loopback means the literal host {@code localhost}, an IPv4 literal in {@code 127.0.0.0/8}, or the
 * IPv6 loopback literal. Names are never resolved, so {@code 0.0.0.0}, LAN names and any other host
 * are refused.
 */
final class LoopbackAddress {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private LoopbackAddress() {}

    static boolean isLoopbackUrl(String url) {
        if (url == null || url.isBlank()) {

            return false;
        }
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException ex) {

            return false;
        }

        return host != null && isLoopbackHost(host);
    }

    static boolean isLoopbackHost(String rawHost) {
        String host = rawHost.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost")) {

            return true;
        }
        if (IPV4.matcher(host).matches()) {
            String[] octets = host.split("\\.");
            for (String octet : octets) {
                if (Integer.parseInt(octet) > 255) {

                    return false;
                }
            }

            return Integer.parseInt(octets[0]) == 127;
        }
        if (host.contains(":") && host.matches("[0-9a-f:.]+")) {
            try {

                // An IP literal: InetAddress parses it without a name lookup.
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (UnknownHostException ex) {

                return false;
            }
        }

        return false;
    }
}
