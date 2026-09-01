package com.acme.awssupport.domain;

/**
 * An expected failure carrying a stable error code, HTTP status, and client-safe message.
 *
 * <p>Adapters and application services use this type to distinguish operational failures from
 * evidence-based abstention, which is represented by an answer status rather than an exception.
 */
public final class SupportException extends RuntimeException {
  private final String code;
  private final int status;

  public SupportException(String code, int status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }
}
