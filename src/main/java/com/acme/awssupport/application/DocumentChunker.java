package com.acme.awssupport.application;

import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.TokenCounter;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Groups extracted blocks into deterministic passages with embedding-token budgets.
 *
 * <p>Targets roughly 350 body tokens without splitting structural blocks; small trailing blocks may
 * overlap adjacent chunks within the same heading. Oversized blocks are rejected rather than
 * silently truncated. This preserves block boundaries but does not prove that every cross-block
 * prerequisite has been retained.
 */
@Component
public class DocumentChunker {
  private final TokenCounter tokens;

  public DocumentChunker(TokenCounter tokens) {
    this.tokens = tokens;
  }

  /**
   * Creates ordered, deterministic chunks; rejects structural blocks exceeding embedding budgets.
   */
  public List<Chunk> chunks(Source source, ParsedDocument document) {
    List<Chunk> result = new ArrayList<>();
    List<Block> buffer = new ArrayList<>();
    for (Block block : document.blocks()) {
      if (!buffer.isEmpty()
          && (!buffer.getFirst().heading().equals(block.heading())
              || tokens.embeddingTokens(text(buffer) + "\n\n" + block.text()) > 350)) {
        result.add(chunk(source, document, buffer, result.size()));
        Block last = buffer.getLast();
        buffer.clear();
        if (last.heading().equals(block.heading())
            && tokens.embeddingTokens(last.text()) <= 50
            && !last.code()) buffer.add(last);
      }
      String input =
          "search_document: " + document.title() + "\n" + block.heading() + "\n" + block.text();
      if (tokens.embeddingTokens(input) > 1900) {
        // Reject rather than sever prerequisites or split a code/table example into misleading
        // fragments.
        throw new IllegalArgumentException(
            "Oversize structural block in "
                + source.id()
                + "; curate the source or improve structural splitting before publishing");
      }
      buffer.add(block);
    }
    if (!buffer.isEmpty()) result.add(chunk(source, document, buffer, result.size()));
    return List.copyOf(result);
  }

  /**
   * Builds the exact prefixed embedding input and derives IDs from source, ordinal, and input hash.
   */
  private Chunk chunk(Source source, ParsedDocument document, List<Block> blocks, int ordinal) {
    String body = text(blocks);
    String heading = blocks.getFirst().heading();
    String input = "search_document: " + document.title() + "\n" + heading + "\n" + body;
    if (tokens.embeddingTokens(input) > 2000)
      throw new IllegalArgumentException("Chunk exceeds embedding context");
    String hash = Hashes.sha256(input);
    return new Chunk(
        Hashes.sha256(source.id() + ":" + ordinal + ":" + hash),
        source.id(),
        heading,
        blocks.getFirst().anchor(),
        ordinal,
        body,
        input,
        hash,
        null);
  }

  private static String text(List<Block> blocks) {
    return String.join("\n\n", blocks.stream().map(Block::text).toList());
  }
}
