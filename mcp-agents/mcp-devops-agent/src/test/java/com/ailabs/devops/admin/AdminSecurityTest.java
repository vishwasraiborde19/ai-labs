package com.ailabs.devops.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ailabs.devops.config.DevopsProperties;

class AdminSecurityTest {

    @TempDir
    Path dir;

    @Test
    void cidrMatching() {
        assertThat(Cidr.parse("192.168.1.0/24").contains("192.168.1.77")).isTrue();
        assertThat(Cidr.parse("192.168.1.0/24").contains("192.168.2.1")).isFalse();
        assertThat(Cidr.parse("10.0.0.0/12").contains("10.15.255.255")).isTrue();
        assertThat(Cidr.parse("10.0.0.0/12").contains("10.16.0.0")).isFalse();
        assertThat(Cidr.parse("127.0.0.0/8").contains("::ffff:127.0.0.1")).isTrue();
        assertThat(Cidr.parse("::1/128").contains("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(Cidr.anyContains(List.of(Cidr.parse("127.0.0.0/8")), "192.168.1.1")).isFalse();
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordIsHashedAndVerified() throws Exception {
        AdminPassword p = new AdminPassword(dir.resolve("admin.json"));
        assertThat(p.isSet()).isFalse();
        p.set("correct horse battery".toCharArray());
        assertThat(p.verify("correct horse battery".toCharArray())).isTrue();
        assertThat(p.verify("wrong".toCharArray())).isFalse();
        assertThat(java.nio.file.Files.readString(dir.resolve("admin.json"))).doesNotContain("horse");
        assertThatThrownBy(() -> p.set("short".toCharArray())).hasMessageContaining("at least 12");
        p.set("root".toCharArray(), 4);
        assertThat(p.verify("root".toCharArray())).isTrue();
        assertThatThrownBy(() -> new DevopsProperties.Admin(null, null, 0, null)).hasMessageContaining("at least 1");
        assertThat(new DevopsProperties.Admin(null, null, null, null).minPasswordLength()).isEqualTo(12);
        assertThat(AdminPassword.generate()).hasSize(20);
    }

    static AdminSecurityFilter filter(String auth, List<String> nets) {
        var props = new DevopsProperties(null, null, null, null, null, null, null,
                new DevopsProperties.Admin(nets, null, null, auth));
        return new AdminSecurityFilter(props, new AdminSessions(props));
    }

    static int status(AdminSecurityFilter f, String ip, String path, boolean csrf) throws Exception {
        var req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(ip);
        if (csrf) {
            req.addHeader(AdminSecurityFilter.CSRF_HEADER, "1");
        }
        var res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void authModeDefaultsToNoneAndRejectsUnknownValues() {
        assertThat(new DevopsProperties.Admin(null, null, null, null).auth()).isEqualTo("none");
        assertThat(new DevopsProperties.Admin(null, null, null, " Password ").auth()).isEqualTo("password");
        assertThatThrownBy(() -> new DevopsProperties.Admin(null, null, null, "oauth")).hasMessageContaining("must be one of");
    }

    @Test
    void passwordlessModeIsLoopbackOnlyButStillNeedsCsrfHeader() throws Exception {
        AdminSecurityFilter f = filter("none", List.of("192.168.1.0/24"));
        assertThat(status(f, "127.0.0.1", "/admin/api/config", true)).isEqualTo(200);
        assertThat(status(f, "0:0:0:0:0:0:0:1", "/admin/api/config", true)).isEqualTo(200);
        assertThat(status(f, "192.168.1.5", "/admin/api/config", true)).isEqualTo(403);
        assertThat(status(f, "127.0.0.1", "/admin/api/config", false)).isEqualTo(403);
    }

    @Test
    void passwordModeNeedsASession() throws Exception {
        AdminSecurityFilter f = filter("password", List.of("192.168.1.0/24"));
        assertThat(status(f, "192.168.1.5", "/admin/api/config", true)).isEqualTo(401);
        assertThat(status(f, "192.168.1.5", "/admin/api/session", true)).isEqualTo(200);
        assertThat(status(f, "10.0.0.1", "/admin", false)).isEqualTo(403);
    }

    @Test
    void samlIsReservedUntilBuilt() {
        assertThatThrownBy(() -> filter("saml", null)).hasMessageContaining("not implemented yet");
    }

    @Test
    void sessionsAndLockout() {
        var props = new DevopsProperties(null, null, null, null, null, null, null, null);
        AdminSessions s = new AdminSessions(props);
        String t = s.create();
        assertThat(s.valid(t)).isTrue();
        assertThat(s.valid("forged")).isFalse();
        s.end(t);
        assertThat(s.valid(t)).isFalse();

        for (int i = 0; i < AdminSessions.MAX_FAILURES; i++) {
            assertThat(s.locked("1.2.3.4")).isFalse();
            s.failed("1.2.3.4");
        }
        assertThat(s.locked("1.2.3.4")).isTrue();
        assertThat(s.locked("5.6.7.8")).isFalse();
    }
}
