package com.acme.awssupport.application;

import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds a complete corpus generation from an explicitly approved source manifest.
 *
 * <p>Downloads and snapshots source HTML, extracts and chunks content, and reuses compatible
 * embedding checkpoints. Publication occurs only after all manifest sources succeed; failures
 * preserve the previously active generation. A cross-process ingestion lease excludes competing
 * publishers.
 */
@Service
public class IngestCorpus {
  private static final Logger log = LoggerFactory.getLogger(IngestCorpus.class);
  private final CorpusRepository repository;
  private final DocumentSource source;
  private final DocumentParser parser;
  private final DocumentChunker chunker;
  private final LocalModel model;
  private final WorkCoordinator locks;
  private final RagProperties properties;
  private final ObjectMapper json;

  public IngestCorpus(
      CorpusRepository repository,
      DocumentSource source,
      DocumentParser parser,
      DocumentChunker chunker,
      LocalModel model,
      WorkCoordinator locks,
      RagProperties properties,
      ObjectMapper json) {
    this.repository = repository;
    this.source = source;
    this.parser = parser;
    this.chunker = chunker;
    this.model = model;
    this.locks = locks;
    this.properties = properties;
    this.json = json;
  }

  /** Runs an explicit operator refresh, without applying the scheduled-refresh due-time gate. */
  public synchronized boolean run(Path manifestPath) throws java.io.IOException {
    return run(manifestPath, false);
  }

  /**
   * Builds and publishes a complete generation under an ingestion lease. Scheduled calls recheck
   * due times after acquiring the lease; false means unchanged or not due.
   */
  public synchronized boolean run(Path manifestPath, boolean scheduled) throws java.io.IOException {
    byte[] manifestBytes = Files.readAllBytes(manifestPath);
    Manifest manifest = json.readValue(manifestBytes, Manifest.class);
    String hash = Hashes.sha256(manifestBytes);
    try (var lease =
        locks.acquire(WorkCoordinator.INGESTION, new Deadline(Duration.ofSeconds(3)), false)) {
      // Another process may have refreshed while this caller was waiting to acquire the lease.
      if (scheduled) {
        Instant last = repository.lastCompleteCheck();
        Instant retry = repository.nextRefreshAttempt();
        if ((last != null && last.plus(properties.refreshInterval()).isAfter(Instant.now()))
            || (retry != null && retry.isAfter(Instant.now()))) return false;
      }
      String profile = model.profile().embeddingProfile();
      UUID job = repository.beginJob(hash);
      List<Document> documents = new ArrayList<>();
      List<Chunk> chunks = new ArrayList<>();
      try {
        Path snapshots = properties.dataDir().resolve("snapshots").toAbsolutePath().normalize();
        Files.createDirectories(snapshots);
        for (Source entry : manifest.sources()) {
          try {
            var previous = repository.previousDocument(entry.id());
            var downloaded = source.fetch(entry, previous);
            String rawHash = Hashes.sha256(downloaded.bytes());
            Path snapshot = snapshots.resolve(rawHash + ".html");
            if (!Files.exists(snapshot)) {
              Path temp = Files.createTempFile(snapshots, "fetch-", ".tmp");
              Files.write(temp, downloaded.bytes());
              Files.move(temp, snapshot, StandardCopyOption.ATOMIC_MOVE);
            }
            ParsedDocument parsed = parser.parse(downloaded.bytes(), entry.url());
            List<Chunk> pageChunks = chunker.chunks(entry, parsed);
            if (chunks.size() + pageChunks.size() > properties.maxChunks())
              throw new IllegalArgumentException("Corpus chunk limit exceeded");
            for (Chunk chunk : pageChunks) {
              Optional<float[]> reused = repository.embedding(chunk.inputHash(), profile);
              float[] vector;
              if (reused.isPresent()) vector = reused.get();
              else {
                vector =
                    model
                        .embed(List.of(chunk.embeddingInput()), new Deadline(Duration.ofMinutes(3)))
                        .getFirst();
                // Checkpoints survive a later page failure; retrying the job need not re-embed
                // identical inputs, but none of these staged chunks is yet publicly active.
                repository.saveEmbedding(chunk.inputHash(), profile, vector);
              }
              chunks.add(chunk.embedded(vector));
            }
            String contentHash =
                Hashes.sha256(
                    entry.service()
                        + entry.url()
                        + parsed.title()
                        + pageChunks.stream().map(c -> c.inputHash() + c.anchor()).toList());
            Instant fetchedAt =
                previous
                    .filter(d -> d.rawHash().equals(rawHash))
                    .map(Document::fetchedAt)
                    .orElse(Instant.now());
            documents.add(
                new Document(
                    entry.id(),
                    entry.service(),
                    entry.url(),
                    parsed.title(),
                    rawHash,
                    contentHash,
                    snapshot.toString(),
                    fetchedAt,
                    downloaded.etag(),
                    downloaded.lastModified()));
            repository.checked(entry.id(), true, null);
            log.info(
                "ingestion job={} source={} chunks={} completed={}/{}",
                job,
                entry.id(),
                pageChunks.size(),
                documents.size(),
                manifest.sources().size());
          } catch (Exception error) {
            repository.checked(entry.id(), false, error.getClass().getSimpleName());
            throw error;
          }
        }
        if (chunks.isEmpty())
          throw new IllegalArgumentException("Empty corpus cannot be published");
        // The repository switches the active pointer only after the complete corpus is committed.
        boolean changed = repository.publish(job, hash, profile, documents, chunks);
        log.info(
            "ingestion job={} published={} documents={} chunks={}",
            job,
            changed,
            documents.size(),
            chunks.size());
        return changed;
      } catch (Exception error) {
        repository.failJob(job, error.getClass().getSimpleName());
        throw error;
      }
    }
  }

  /**
   * Fetches, parses, and chunks all manifest sources without embedding or publishing a generation.
   * This diagnostic may access the network.
   */
  public void validate(Path manifestPath) throws java.io.IOException {
    Manifest manifest = json.readValue(Files.readAllBytes(manifestPath), Manifest.class);
    List<String> failed = new ArrayList<>();
    int total = 0;
    for (Source entry : manifest.sources()) {
      try {
        var download = source.fetch(entry, repository.previousDocument(entry.id()));
        var chunks = chunker.chunks(entry, parser.parse(download.bytes(), entry.url()));
        total += chunks.size();
      } catch (Exception error) {
        failed.add(entry.id());
        log.warn("manifest validation source={} error={}", entry.id(), error.getMessage());
      }
    }
    log.info(
        "manifest validation sources={} chunks={} failed={}",
        manifest.sources().size(),
        total,
        failed);
    if (!failed.isEmpty())
      throw new IllegalArgumentException(
          "Manifest validation failed for " + failed.size() + " source(s)");
  }
}
