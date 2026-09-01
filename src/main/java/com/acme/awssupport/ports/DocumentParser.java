package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Types.ParsedDocument;

/** Converts downloaded HTML into titled structural blocks without performing model inference. */
public interface DocumentParser {
  /** Extracts substantive HTML content into ordered blocks; rejects unsupported or empty pages. */
  ParsedDocument parse(byte[] html, String sourceUrl);
}
