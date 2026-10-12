package dev.arctic.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Only accepts requests addressed to {@code 127.0.0.1:<port>} that carry the serve token.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LocalAccessFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte[] token;
    private final String expectedHost;

    LocalAccessFilter(@Value("${arctic.serve.token}") String token, @Value("${server.port}") int port) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.expectedHost = "127.0.0.1:" + port;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!expectedHost.equals(request.getHeader(HttpHeaders.HOST))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden_host", "Host header must be " + expectedHost);
            return;
        }
        if (!hasValidToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hasValidToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private static void reject(HttpServletResponse response, int status, String kind, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write("{\"kind\":\"" + kind + "\",\"message\":\"" + message + "\"}");
    }
}
