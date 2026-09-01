package com.acme.awssupport.adapters.inbound;

import com.github.benmanes.caffeine.cache.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies browser-facing safeguards for the loopback-only deployment profile.
 *
 * <p>Checks Host and Origin, issues bounded in-memory CSRF sessions, restricts mutation endpoints,
 * and caps JSON request bodies. These controls do not provide user authentication or tenant
 * isolation; network binding remains a separate server configuration requirement.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalSecurityFilter extends OncePerRequestFilter {
  private final Set<String> hosts;
  private final Cache<String, String> sessions =
      Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofHours(8)).build();
  private final SecureRandom random = new SecureRandom();

  public LocalSecurityFilter(@Value("${server.port}") int port) {
    hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Cache-Control", "no-store");
    String host = request.getHeader("Host");
    String origin = request.getHeader("Origin");
    if (!hosts.contains(host == null ? "" : host)
        || (origin != null && !origin.equals("http://" + host))) {
      reject(response, 403, "FORBIDDEN", "Only same-origin local requests are accepted.");
      return;
    }
    if (request.getRequestURI().equals("/api/v1/session") && request.getMethod().equals("GET")) {
      String session = token();
      String csrf = token();
      sessions.put(session, csrf);
      response.addHeader(
          "Set-Cookie", "RAG_SESSION=" + session + "; Path=/; HttpOnly; SameSite=Strict");
      response.setContentType("application/json");
      response.getWriter().write("{\"csrfToken\":\"" + csrf + "\"}");
      return;
    }
    if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
      String session =
          request.getCookies() == null
              ? ""
              : Arrays.stream(request.getCookies())
                  .filter(c -> c.getName().equals("RAG_SESSION"))
                  .map(Cookie::getValue)
                  .findFirst()
                  .orElse("");
      String expected = sessions.getIfPresent(session);
      String supplied = request.getHeader("X-CSRF-Token");
      if (expected == null
          || supplied == null
          || !MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.UTF_8),
              supplied.getBytes(StandardCharsets.UTF_8))) {
        reject(response, 403, "CSRF_FAILED", "Refresh the page to establish a local session.");
        return;
      }
      if (!request.getMethod().equals("POST") || !request.getRequestURI().equals("/api/v1/chat")) {
        reject(response, 405, "METHOD_NOT_ALLOWED", "Unsupported operation.");
        return;
      }
      if (request.getContentType() == null
          || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
        reject(response, 415, "CONTENT_TYPE", "Use application/json.");
        return;
      }
      byte[] body = request.getInputStream().readNBytes(32769);
      if (body.length > 32768) {
        reject(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds 32 KiB.");
        return;
      }
      chain.doFilter(new BufferedRequest(request, body), response);
      return;
    }
    chain.doFilter(request, response);
  }

  private String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response
        .getWriter()
        .write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"retryable\":false}");
  }

  /** Replays the already size-checked body so controller JSON parsing can read it normally. */
  private static class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream bytes = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return bytes.read();
        }

        @Override
        public boolean isFinished() {
          return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Blocking requests only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
