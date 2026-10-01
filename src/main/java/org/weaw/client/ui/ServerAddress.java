package org.weaw.client.ui;

import java.net.IDN;

/** Parses direct-connect endpoints without performing blocking DNS resolution. */
public record ServerAddress(String host, int port) {
    public ServerAddress {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        host = host.trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be in range [1, 65535]");
        }
        validateHost(host);
    }

    public static ServerAddress parse(String value, int defaultPort) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Server address is required");
        }
        if (normalized.startsWith("[")) {
            int closing = normalized.indexOf(']');
            if (closing < 2) {
                throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            }
            String host = normalized.substring(1, closing);
            if (closing == normalized.length() - 1) {
                return new ServerAddress(host, defaultPort);
            }
            if (normalized.charAt(closing + 1) != ':') {
                throw new IllegalArgumentException("Unexpected text after IPv6 address");
            }
            return new ServerAddress(host, parsePort(normalized.substring(closing + 2)));
        }

        int firstColon = normalized.indexOf(':');
        int lastColon = normalized.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return new ServerAddress(
                    normalized.substring(0, firstColon),
                    parsePort(normalized.substring(firstColon + 1))
            );
        }
        return new ServerAddress(normalized, defaultPort);
    }

    public String display() {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid server port", exception);
        }
    }

    private static void validateHost(String host) {
        if (host.contains(":")) {
            validateIpv6(host);
            return;
        }
        if (host.matches("[0-9.]+")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            for (String octet : octets) {
                try {
                    if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("Invalid IPv4 address");
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid IPv4 address", exception);
                }
            }
            return;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid host name", exception);
        }
        if (ascii.length() > 253) {
            throw new IllegalArgumentException("Invalid host name");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
                throw new IllegalArgumentException("Invalid host name");
            }
        }
    }

    private static void validateIpv6(String host) {
        String address = host;
        int zoneSeparator = address.indexOf('%');
        if (zoneSeparator >= 0) {
            if (zoneSeparator == address.length() - 1 || address.indexOf('%', zoneSeparator + 1) >= 0) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            address = address.substring(0, zoneSeparator);
        }
        if (!address.matches("[0-9A-Fa-f:]+") || address.indexOf(':') < 0
                || address.indexOf("::") != address.lastIndexOf("::")) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        boolean compressed = address.contains("::");
        String[] groups = address.split(":", -1);
        int populatedGroups = 0;
        for (String group : groups) {
            if (!group.isEmpty()) {
                if (group.length() > 4 || !group.matches("[0-9A-Fa-f]{1,4}")) {
                    throw new IllegalArgumentException("Invalid IPv6 address");
                }
                populatedGroups++;
            }
        }
        if ((!compressed && populatedGroups != 8) || (compressed && populatedGroups >= 8)) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
    }
}
