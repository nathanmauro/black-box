package dev.nathan.sbaagentic.platform.internal.adapter.out.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loopback means the literal host {@code localhost}, an IPv4 literal in {@code 127.0.0.0/8}, or the
 * IPv6 loopback {@code ::1} in any RFC 4291 spelling without a zone or an embedded IPv4 part. Hosts
 * are compared as text and never resolved, so {@code 0.0.0.0}, LAN names and any other host are
 * refused.
 */
final class LoopbackAddress {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-f]{1,4}");
    private static final List<Integer> IPV6_LOOPBACK = List.of(0, 0, 0, 0, 0, 0, 0, 1);

    private LoopbackAddress() {}

    static boolean isLoopbackUrl(String url) {
        if (url == null || url.isBlank()) {

            return false;
        }
        try {

            return isLoopbackUri(URI.create(url.strip()));
        } catch (IllegalArgumentException ex) {

            return false;
        }
    }

    static boolean isLoopbackUri(URI uri) {

        return uri != null && uri.getHost() != null && isLoopbackHost(uri.getHost());
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

        return IPV6_LOOPBACK.equals(ipv6Groups(host));
    }

    /** The eight 16-bit groups of an RFC 4291 IPv6 text address, or null for anything else. */
    private static List<Integer> ipv6Groups(String host) {
        String[] halves = host.split("::", -1);
        if (halves.length > 2) {

            return null;
        }
        List<Integer> head = hexGroups(halves[0]);
        List<Integer> tail = halves.length == 2 ? hexGroups(halves[1]) : List.of();
        if (head == null || tail == null) {

            return null;
        }
        int written = head.size() + tail.size();
        // Without "::" all eight groups are written; "::" stands for at least one zero group.
        if (halves.length == 1 ? written != 8 : written > 7) {

            return null;
        }
        List<Integer> groups = new ArrayList<>(head);
        while (groups.size() < 8 - tail.size()) {
            groups.add(0);
        }
        groups.addAll(tail);

        return groups;
    }

    /** Colon-separated groups of one to four hex digits; an empty part has none; null when malformed. */
    private static List<Integer> hexGroups(String part) {
        List<Integer> groups = new ArrayList<>();
        if (part.isEmpty()) {

            return groups;
        }
        for (String group : part.split(":", -1)) {
            if (!HEX_GROUP.matcher(group).matches()) {

                return null;
            }
            groups.add(Integer.parseInt(group, 16));
        }

        return groups;
    }
}
