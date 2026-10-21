package com.lendingplatform.quoteapp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser security headers (ARCHITECTURE §9.4). The CSP allows only same-origin scripts and styles, which is why the
 * page has no inline script, no {@code hx-on} attributes, and htmx runs with {@code allowEval=false} and
 * {@code includeIndicatorStyles=false}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "form-action 'self'; frame-ancestors 'none'; base-uri 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
