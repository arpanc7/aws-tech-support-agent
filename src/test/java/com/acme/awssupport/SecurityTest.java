package com.acme.awssupport;

import static org.assertj.core.api.Assertions.*;

import com.acme.awssupport.adapters.inbound.LocalSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

/** Checks local browser request safeguards using mock servlet requests and responses. */
class SecurityTest {
  @Test
  void rejectsBadHostAndCrossOrigin() throws Exception {
    for (String origin : new String[] {"https://attacker.example", "null"}) {
      var request = new MockHttpServletRequest("GET", "/api/v1/session");
      request.addHeader("Host", "127.0.0.1:8080");
      request.addHeader("Origin", origin);
      var response = new MockHttpServletResponse();
      new LocalSecurityFilter(8080).doFilter(request, response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(403);
    }
  }

  @Test
  void requiresCsrfAndRejectsOversizeBody() throws Exception {
    var filter = new LocalSecurityFilter(8080);
    var session = new MockHttpServletRequest("GET", "/api/v1/session");
    session.addHeader("Host", "127.0.0.1:8080");
    var response = new MockHttpServletResponse();
    filter.doFilter(session, response, new MockFilterChain());
    String token =
        new ObjectMapper().readTree(response.getContentAsString()).get("csrfToken").asText();
    String cookie = response.getHeader("Set-Cookie").split(";")[0].split("=")[1];
    var request = new MockHttpServletRequest("POST", "/api/v1/chat");
    request.addHeader("Host", "127.0.0.1:8080");
    request.setContentType("application/json");
    var denied = new MockHttpServletResponse();
    filter.doFilter(request, denied, new MockFilterChain());
    assertThat(denied.getStatus()).isEqualTo(403);
    request.setCookies(new Cookie("RAG_SESSION", cookie));
    request.addHeader("X-CSRF-Token", token);
    request.setContent(new byte[32769]);
    var tooLarge = new MockHttpServletResponse();
    filter.doFilter(request, tooLarge, new MockFilterChain());
    assertThat(tooLarge.getStatus()).isEqualTo(413);
  }
}
