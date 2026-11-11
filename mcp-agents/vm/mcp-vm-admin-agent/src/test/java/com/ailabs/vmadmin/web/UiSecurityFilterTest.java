package com.ailabs.vmadmin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ailabs.vmadmin.config.AdminProperties;

class UiSecurityFilterTest {

    private final UiSecurityFilter filter = new UiSecurityFilter(new AdminProperties(null, null, null, null, null, null, null, null));

    private MockHttpServletResponse call(String ip, String path, boolean header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(ip);
        if (header) {
            req.addHeader(UiSecurityFilter.CSRF_HEADER, "1");
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        if (res.getStatus() == 200) {
            assertNotNull(chain.getRequest(), "request passed on");
        } else {
            assertNull(chain.getRequest(), "request stopped");
        }
        return res;
    }

    @Test
    void onlyThisMachineAndOnlyWithHeader() throws Exception {
        assertEquals(200, call("127.0.0.1", "/ui/api/fleet", true).getStatus());
        assertEquals(200, call("::1", "/ui/api/fleet", true).getStatus());
        assertEquals(200, call("127.0.0.1", "/ui/index.html", false).getStatus());
        assertEquals(403, call("127.0.0.1", "/ui/api/fleet", false).getStatus());
        assertEquals(403, call("192.168.1.20", "/ui/api/fleet", true).getStatus());
        assertEquals(403, call("192.168.1.20", "/ui/index.html", false).getStatus());
        MockHttpServletResponse r = call("127.0.0.1", "/ui/api/session", true);
        assertNotNull(r.getHeader("Content-Security-Policy"));
        assertEquals("DENY", r.getHeader("X-Frame-Options"));
    }

    @Test
    void samlIsReservedAndUnknownModesFail() {
        assertThrows(IllegalStateException.class, () -> new UiSecurityFilter(
                new AdminProperties(new AdminProperties.Ui("saml"), null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new AdminProperties.Ui("password"));
        assertEquals("none", new AdminProperties.Ui(null).auth());
    }

    @Test
    void mcpNeedsConfiguredToken() throws Exception {
        McpAuthFilter off = new McpAuthFilter(new AdminProperties(null, null, null, null, null, null, null, null));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();
        off.doFilter(req, res, new MockFilterChain());
        assertEquals(503, res.getStatus());
    }
}
