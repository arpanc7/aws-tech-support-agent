package com.acme.awssupport.ports;

/** Counts text with the pinned embedding and generation tokenizers used for budget checks. */
public interface TokenCounter {
  /** Counts an embedding input with the embedding model's special-token convention. */
  int embeddingTokens(String text);

  /** Counts the fully formatted generation prompt without adding another chat template. */
  int generationTokens(String text);

  /** Returns a stable tokenizer-pair identity for versioning caches and indexes. */
  String digest();
}
