package com.lendingplatform.vendormock.security;

import com.lendingplatform.vendormock.web.CorrelationIdFilter;
import com.lendingplatform.vendormock.web.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request without a valid {@code api-key} header before anything else can happen.
 *
 * <p>Runs after {@link CorrelationIdFilter} (so the 401 carries a reference) and before the chaos filter: auth is
 * deterministic, so a request without a key always gets a 401, never a random 503.
 *
 * <p>Keys are compared as SHA-256 digests with {@link MessageDigest#isEqual}, which is constant time and, because
 * digests are all the same length, doesn't leak the key's length either. Missing, blank and wrong keys get the same
 * response so a caller learns nothing about which it was. The key itself is never logged.
 */
@Component
@Order(ApiKeyFilter.ORDER)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final int ORDER = CorrelationIdFilter.ORDER + 10;
    public static final String HEADER = "api-key";

    private static final String HEALTH_PATH = "/actuator/health";

    private final List<byte[]> acceptedKeyDigests;
    private final ProblemResponseWriter problems;

    public ApiKeyFilter(ApiKeyProperties properties, ProblemResponseWriter problems) {
        this.acceptedKeyDigests = properties.apiKeys().stream().map(ApiKeyFilter::sha256).toList();
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Default deny: only the health endpoint is open, so load balancers can probe without a key.
        String path = request.getRequestURI();
        return path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAccepted(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
        } else {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid api-key header is required.");
        }
    }

    private boolean isAccepted(String presentedKey) {
        if (presentedKey == null) {
            return false;
        }
        byte[] presentedDigest = sha256(presentedKey);
        boolean accepted = false;
        // Check every accepted key, without returning early, so the time taken doesn't reveal which key matched.
        for (byte[] acceptedDigest : acceptedKeyDigests) {
            accepted |= MessageDigest.isEqual(presentedDigest, acceptedDigest);
        }
        return accepted;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
