package com.tradewind.common.web;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Forwards the current request id on service-to-service HTTP calls so a trace spans every hop. */
public class RequestIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestIds.MDC_KEY);
        if (requestId != null && !request.getHeaders().containsKey(RequestIds.HEADER)) {
            request.getHeaders().set(RequestIds.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
