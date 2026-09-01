package com.acme.awssupport.adapters.inbound;

import com.acme.awssupport.domain.SupportException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * Maps controller failures to a consistent API error body without exposing internal exception
 * details.
 *
 * <p>Expected support errors keep their public codes; database, malformed-request, and unexpected
 * failures receive controlled messages. Evidence abstentions are normal chat responses, not errors.
 */
@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(SupportException.class)
  public ResponseEntity<?> support(SupportException error) {
    return response(error.status(), error.code(), error.getMessage());
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<?> database(DataAccessException error) {
    return response(503, "DATABASE_UNAVAILABLE", "The local database is unavailable.");
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  public ResponseEntity<?> invalid(Exception error) {
    return response(400, "INVALID_REQUEST", "The request does not match the API contract.");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception error) {
    return response(500, "INTERNAL_ERROR", "The request could not be completed safely.");
  }

  private ResponseEntity<?> response(int status, String code, String message) {
    var builder = ResponseEntity.status(status);
    if (status == 429) builder.header("Retry-After", "5");
    return builder.body(
        Map.of(
            "requestId",
            UUID.randomUUID().toString(),
            "code",
            code,
            "message",
            message,
            "retryable",
            status == 429 || status >= 503));
  }
}
