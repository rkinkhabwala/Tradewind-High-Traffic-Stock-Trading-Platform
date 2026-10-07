package com.tradewind.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

/** Puts the request id in the logging MDC and echoes it on the response. Register as the first filter. */
public class RequestIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = RequestIds.acceptOrGenerate(((HttpServletRequest) request).getHeader(RequestIds.HEADER));
        MDC.put(RequestIds.MDC_KEY, requestId);
        ((HttpServletResponse) response).setHeader(RequestIds.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }
}
