package com.lendingplatform.vendormock.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 Problem Details from servlet filters, which run outside Spring MVC and so can't use
 * {@code ProblemDetailsAdvice}. Produces the same shape as the advice: standard members plus {@code code} and
 * {@code correlationId}.
 */
@Component
public class ProblemResponseWriter {

    private final JsonMapper jsonMapper;

    public ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String code, String detail) throws IOException {
        // "type" is omitted, as Spring omits it: an absent type means "about:blank" (RFC 9457 §3.1.1).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("correlationId", CorrelationIdFilter.current());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
