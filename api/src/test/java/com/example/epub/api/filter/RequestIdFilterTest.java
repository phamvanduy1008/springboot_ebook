package com.example.epub.api.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    @Test void acceptsSafeIdAndCleansMdcEvenWhenDownstreamFails() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Request-ID", "client-123");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> new RequestIdFilter().doFilter(request, response, (req, res) -> {
            assertEquals("client-123", MDC.get("requestId")); throw new IllegalStateException("test");
        }));
        assertEquals("client-123", response.getHeader("X-Request-ID")); assertNull(MDC.get("requestId"));
    }
    @Test void replacesInvalidHeaderRatherThanEchoingIt() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Request-ID", "bad\r\nheader");
        var response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> {});
        assertTrue(response.getHeader("X-Request-ID").matches("[a-f0-9-]{36}"));
    }
}
