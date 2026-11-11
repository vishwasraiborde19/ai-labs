package com.ailabs.devops.admin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** IPv4/IPv6 network such as 192.168.1.0/24; matches addresses inside it. */
public record Cidr(byte[] network, int prefix) {

    public static Cidr parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        try {
            byte[] addr = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : addr.length * 8;
            if (prefix < 0 || prefix > addr.length * 8) {
                throw new IllegalArgumentException("Bad prefix in " + cidr);
            }
            return new Cidr(addr, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Bad network '" + cidr + "'", e);
        }
    }

    public boolean contains(String ip) {
        try {
            return contains(InetAddress.getByName(ip).getAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }

    boolean contains(byte[] addr) {
        if (addr.length == 16 && network.length == 4 && isMappedV4(addr)) {
            addr = java.util.Arrays.copyOfRange(addr, 12, 16);
        }
        if (addr.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (addr[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (addr[full] & mask) == (network[full] & mask);
    }

    private static boolean isMappedV4(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF;
    }

    public static boolean anyContains(List<Cidr> nets, String ip) {
        return ip != null && nets.stream().anyMatch(n -> n.contains(ip));
    }
}
