package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.Types.*;
import java.util.List;

/**
 * Model boundary separating vector generation from evidence selection and coverage verification.
 *
 * <p>Callers supply the evidence explicitly. Implementations must not add outside knowledge or
 * expose a model-generated answer as a trusted citation.
 */
public interface LocalModel {
  /**
   * Validates installed model identities and returns their compatibility profile without inference.
   */
  ModelProfile profile();

  /**
   * Embeds prepared inputs including task prefixes, returning one vector per input in order.
   * Oversized inputs must be rejected rather than silently truncated.
   */
  List<float[]> embed(List<String> inputs, Deadline deadline);

  /** Asks the generator to choose sufficient evidence; the returned selection remains untrusted. */
  Selection select(Question question, List<Evidence> evidence, Deadline deadline);

  /** Checks whether selected excerpts support the question; uncertainty must not return true. */
  boolean verify(Question question, List<Evidence> evidence, Deadline deadline);
}
