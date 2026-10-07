package com.tradewind.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void keepsWellFormedIncomingIdAndExposesItToLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(RequestIds.MDC_KEY)));

        assertThat(seenInMdc).hasValue("abc-123");
        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesMissingOrMalformedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "bad id\nwith newline");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIds.HEADER)).hasSize(36).doesNotContain(" ");
    }
}
