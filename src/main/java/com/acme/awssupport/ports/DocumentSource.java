package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Types.*;
import java.util.Optional;

/** Fetches an approved source, optionally reusing a previously verified local snapshot. */
public interface DocumentSource {
  /** Downloaded HTML bytes and optional HTTP validators for subsequent conditional requests. */
  record Download(byte[] bytes, String etag, String lastModified) {}

  /**
   * Loads an approved source, optionally reusing its verified snapshot after a not-modified
   * response.
   */
  Download fetch(Source source, Optional<Document> previous);
}
