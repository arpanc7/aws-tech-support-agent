package com.acme.awssupport.adapters.outbound;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.ports.TokenCounter;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Lazily loads checksum-pinned tokenizer files for Nomic and Qwen from local storage.
 *
 * <p>Embedding counts include special tokens; generation counts operate on the complete raw prompt.
 * Truncation is disabled so an oversized input cannot silently pass budget checks. Native tokenizer
 * access is synchronized and resources are closed when the application context stops.
 */
@Component
public class LocalTokenizers implements TokenCounter {
  private final Path directory;
  private HuggingFaceTokenizer embedding;
  private HuggingFaceTokenizer generation;
  private String digest;

  public LocalTokenizers(RagProperties properties) {
    directory = properties.dataDir().resolve("tokenizers");
  }

  private synchronized void initialize() {
    if (embedding != null) return;
    try {
      Path e = directory.resolve("nomic/tokenizer.json");
      Path g = directory.resolve("qwen/tokenizer.json");
      if (!Files.isRegularFile(e) || !Files.isRegularFile(g))
        throw new IOException("Missing tokenizer artifacts");
      String embeddingHash = Hashes.sha256(Files.readAllBytes(e));
      String generationHash = Hashes.sha256(Files.readAllBytes(g));
      if (!embeddingHash.equals("d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66")
          || !generationHash.equals(
              "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4")) {
        throw new IOException("Tokenizer checksum differs from the locked model profile");
      }
      Map<String, String> options =
          Map.of("truncation", "false", "padding", "false", "maxLength", "131072");
      generation = HuggingFaceTokenizer.newInstance(g, options);
      embedding = HuggingFaceTokenizer.newInstance(e, options);
      digest = Hashes.sha256(embeddingHash + generationHash);
    } catch (IOException | RuntimeException error) {
      if (generation != null) {
        generation.close();
        generation = null;
      }
      throw new SupportException(
          "TOKENIZER_UNAVAILABLE",
          503,
          "Local tokenizers are missing or incompatible. Run model setup.");
    }
  }

  @Override
  public synchronized int embeddingTokens(String text) {
    initialize();
    return embedding.encode(text, true, false).getIds().length;
  }

  @Override
  public synchronized int generationTokens(String text) {
    initialize();
    return generation.encode(text, false, false).getIds().length;
  }

  @Override
  public synchronized String digest() {
    initialize();
    return digest;
  }

  @PreDestroy
  public synchronized void close() {
    if (embedding != null) embedding.close();
    if (generation != null) generation.close();
  }
}
