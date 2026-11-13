package com.ailabs.vmadmin.pve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProxmoxParsersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void guestAgentIpsSkipLoopbackDockerAndIpv6() throws Exception {
        JsonNode data = json("""
                {"result":[
                  {"name":"lo","ip-addresses":[{"ip-address-type":"ipv4","ip-address":"127.0.0.1"}]},
                  {"name":"ens18","ip-addresses":[
                     {"ip-address-type":"ipv4","ip-address":"192.168.1.137","prefix":24},
                     {"ip-address-type":"ipv6","ip-address":"fe80::1"}]},
                  {"name":"docker0","ip-addresses":[{"ip-address-type":"ipv4","ip-address":"172.17.0.1"}]},
                  {"name":"ens19","ip-addresses":[{"ip-address-type":"ipv4","ip-address":"10.0.0.5"}]}
                ]}""");
        assertEquals(List.of("192.168.1.137", "10.0.0.5"), ProxmoxService.agentIps(data));
    }

    @Test
    void containerInterfaces() throws Exception {
        JsonNode data = json("""
                [{"name":"lo","inet":"127.0.0.1/8"},{"name":"eth0","inet":"192.168.1.60/24","hwaddr":"bc:24:11:00:00:01"},
                 {"name":"eth1"}]""");
        assertEquals(List.of("192.168.1.60"), ProxmoxService.lxcIps(data));
    }

    @Test
    void staticAddressesFromCloudInitAndLxcConfig() throws Exception {
        JsonNode qemu = json("""
                {"ipconfig0":"ip=192.168.1.44/24,gw=192.168.1.1","ipconfig1":"ip=dhcp","name":"installer"}""");
        assertEquals(List.of("192.168.1.44"), ProxmoxService.configIps(qemu, "ipconfig"));
        JsonNode lxc = json("""
                {"net0":"name=eth0,bridge=vmbr0,hwaddr=BC:24:11:AA:BB:CC,ip=192.168.1.70/24,gw=192.168.1.1,type=veth"}""");
        assertEquals(List.of("192.168.1.70"), ProxmoxService.configIps(lxc, "net"));
        assertTrue(ProxmoxService.configIps(json("{\"net0\":\"name=eth0,ip=dhcp\"}"), "net").isEmpty());
    }

    @Test
    void tags() {
        assertEquals(List.of("k8s", "prod"), ProxmoxService.splitTags("k8s;prod"));
        assertTrue(ProxmoxService.splitTags("").isEmpty());
    }

    @Test
    void propertiesNormaliseEndpointsAndRequireHttps() {
        ProxmoxProperties p = new ProxmoxProperties(
                List.of("https://192.168.1.30:8006/", "https://192.168.1.40:8006/api2/json", "https://192.168.1.30:8006"),
                "path:/tmp/t", null, null, null, null);
        assertEquals(List.of("https://192.168.1.30:8006", "https://192.168.1.40:8006"), p.endpoints());
        assertTrue(p.configured());
        assertFalse(new ProxmoxProperties(null, null, null, null, null, null).configured());
        assertThrows(IllegalArgumentException.class,
                () -> new ProxmoxProperties(List.of("http://192.168.1.30:8006"), "env:X", null, null, null, null));
    }
}
