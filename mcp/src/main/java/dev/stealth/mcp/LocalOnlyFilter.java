package dev.stealth.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the server to local clients the user registered (MCP spec, Streamable HTTP security):
 *
 * <ul>
 *   <li>{@code Host} must be this server's loopback address, which defeats DNS rebinding: a web
 *       page that rebinds {@code evil.example} to 127.0.0.1 still sends {@code Host: evil.example}
 *   <li>{@code Origin}, which browsers send and MCP clients don't, must be absent or local
 *   <li>{@code Authorization: Bearer <token>} must match {@code ~/.stealth/mcp-token}, except on
 *       {@code /status}, which says only that a stealth server is running
 * </ul>
 */
class LocalOnlyFilter extends OncePerRequestFilter {

    static final String STATUS_PATH = "/status";

    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;
    private final byte[] expectedAuthorization;

    LocalOnlyFilter(int port, String token) {
        this.allowedHosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
        this.allowedOrigins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
        this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }
        if (!STATUS_PATH.equals(request.getRequestURI())) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null
                    || !MessageDigest.isEqual(
                            expectedAuthorization,
                            authorization.getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                reject(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Missing or wrong token: run `stealth mcp install` to register this"
                                + " client with the token in ~/.stealth/mcp-token");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message + "\n");
    }
}
