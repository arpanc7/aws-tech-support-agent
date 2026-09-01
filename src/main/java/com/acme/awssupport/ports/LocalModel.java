package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.Types.*;
import java.util.List;

/**
 * Model boundary separating vector generation from bounded research and answer stages.
 *
 * <p>Callers supply evidence explicitly. Model-proposed searches, claims, and citations remain
 * untrusted until the application validates their shape, provenance, and grounding.
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

  /** Decides whether initial evidence is enough or one additional local search round is useful. */
  ResearchDecision decide(Question question, List<Evidence> evidence, Deadline deadline);

  /** Drafts concise claims using only supplied evidence and model-selected evidence aliases. */
  AnswerDraft answer(Question question, List<Evidence> evidence, Deadline deadline);

  /** Reviews the complete draft against its cited evidence; uncertainty must not return true. */
  boolean verify(Question question, AnswerDraft draft, List<Evidence> evidence, Deadline deadline);
}
