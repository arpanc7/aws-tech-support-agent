package com.acme.awssupport.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration bound from the {@code rag} section of application.yaml and startup overrides.
 *
 * <p>Collects model names, local paths, refresh scheduling, request deadlines, and retrieval/token
 * budgets. Similarity thresholds are retrieval heuristics, not probabilities of answer correctness.
 */
@ConfigurationProperties("rag")
public record RagProperties(
    String ollamaUrl,
    String chatModel,
    String embeddingModel,
    Path dataDir,
    Path manifest,
    Duration requestTimeout,
    Duration refreshInterval,
    boolean refreshEnabled,
    int contextTokens,
    int maxEvidenceTokens,
    int maxChunks,
    double minimumSimilarity,
    double semanticCacheSimilarity) {}
