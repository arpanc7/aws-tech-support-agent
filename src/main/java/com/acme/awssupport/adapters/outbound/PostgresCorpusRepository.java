package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.CorpusRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL/pgvector implementation of corpus storage and hybrid passage retrieval.
 *
 * <p>Combines exact cosine search, English full-text search, and identifier matches using
 * reciprocal rank fusion. Queries filter by generation, service, and revocation state. New
 * document/chunk rows and the active-generation pointer are published in one transaction.
 */
@Repository
public class PostgresCorpusRepository implements CorpusRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final TransactionTemplate transaction;

  public PostgresCorpusRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.transaction = transaction;
  }

  @Override
  public Generation active() {
    return jdbc
        .query(
            "SELECT g.*, a.policy_epoch FROM active_corpus a JOIN corpus_generation g ON g.id=a.generation_id WHERE g.state='READY'",
            (rs, n) ->
                new Generation(
                    rs.getObject("id", UUID.class),
                    rs.getString("profile"),
                    rs.getString("manifest_hash"),
                    rs.getTimestamp("published_at").toInstant(),
                    rs.getLong("policy_epoch")))
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new SupportException(
                    "CORPUS_NOT_READY", 503, "No corpus is ready. Run ingestion first."));
  }

  @Override
  public boolean isValid(Generation g, List<String> sources) {
    Boolean valid =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM corpus_generation g, active_corpus a WHERE g.id=? AND g.state='READY' AND a.policy_epoch=?)",
            Boolean.class,
            g.id(),
            g.epoch());
    if (!Boolean.TRUE.equals(valid)) return false;
    return sources.isEmpty()
        || named.queryForObject(
                "SELECT count(*) FROM revoked_source WHERE source_id IN (:ids)",
                Map.of("ids", sources),
                Integer.class)
            == 0;
  }

  @Override
  public CorpusStatus status() {
    Generation g;
    try {
      g = active();
    } catch (SupportException missing) {
      return new CorpusStatus(
          null, null, lastCompleteCheck(), 0, 0, Map.of(), true, "EMPTY", lastRefreshError());
    }
    Map<String, Integer> services = new TreeMap<>();
    jdbc.query(
        "SELECT service,count(*) AS n FROM document WHERE generation_id=? GROUP BY service",
        rs -> {
          services.put(rs.getString("service"), rs.getInt("n"));
        },
        g.id());
    int docs = services.values().stream().mapToInt(Integer::intValue).sum();
    int chunks =
        jdbc.queryForObject(
            "SELECT count(*) FROM chunk WHERE generation_id=?", Integer.class, g.id());
    Instant checked = lastCompleteCheck();
    return new CorpusStatus(
        g.id(),
        g.publishedAt(),
        checked,
        docs,
        chunks,
        services,
        checked == null || checked.isBefore(Instant.now().minusSeconds(7 * 86400L)),
        "READY",
        lastRefreshError());
  }

  private String lastRefreshError() {
    return jdbc
        .query(
            "SELECT state,error FROM ingestion_job ORDER BY started_at DESC LIMIT 1",
            (rs, row) ->
                rs.getString("state").equals("FAILED")
                    ? Objects.toString(rs.getString("error"), "Refresh failed")
                    : "")
        .stream()
        .findFirst()
        .orElse("");
  }

  /**
   * Combines dense, lexical, and identifier rankings. The returned similarity field is cosine
   * similarity, not the combined rank-fusion score.
   */
  @Override
  public List<Evidence> retrieve(
      Generation g, String query, String service, float[] vector, List<String> candidateIds) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("generation", g.id());
    parameters.put("service", service);
    parameters.put("vector", vectorLiteral(vector));
    parameters.put("query", query);
    String lexicalQuery =
        java.util.regex.Pattern.compile("[\\p{L}\\p{N}_-]{2,}")
            .matcher(query)
            .results()
            .map(m -> m.group())
            .limit(100)
            .collect(java.util.stream.Collectors.joining(" OR "));
    parameters.put("lexicalQuery", lexicalQuery);
    String base =
        " FROM chunk c JOIN document d ON d.generation_id=c.generation_id AND d.source_id=c.source_id WHERE c.generation_id=:generation AND (:service='' OR d.service=:service) AND NOT EXISTS(SELECT 1 FROM revoked_source r WHERE r.source_id=c.source_id)";
    if (!candidateIds.isEmpty()) {
      base += " AND c.id IN (:ids)";
      parameters.put("ids", candidateIds);
    }
    String columns =
        "SELECT c.id,c.source_id,d.service,d.title,d.url,c.heading,c.anchor,c.content,d.fetched_at,1-(c.embedding <=> CAST(:vector AS vector)) AS similarity";
    List<Evidence> dense =
        named.query(
            columns + base + " ORDER BY c.embedding <=> CAST(:vector AS vector),c.id LIMIT 40",
            parameters,
            this::evidence);
    List<Evidence> lexical =
        named.query(
            columns
                + base
                + " AND c.terms_english @@ websearch_to_tsquery('english',:lexicalQuery) ORDER BY ts_rank_cd(c.terms_english,websearch_to_tsquery('english',:lexicalQuery),32) DESC,c.id LIMIT 40",
            parameters,
            this::evidence);
    // PostgreSQL tokenization may split action names; preserve exact identifier hits separately.
    List<String> identifiers =
        java.util.regex.Pattern.compile(
                "[A-Za-z][A-Za-z0-9_-]*:[A-Za-z0-9_*/:-]+|\\b[A-Z][A-Za-z]*[A-Z][A-Za-z]+\\b")
            .matcher(query)
            .results()
            .map(m -> m.group())
            .filter(
                identifier ->
                    !Set.of(
                            "AWS", "IAM", "VPC", "JSON", "HTTP", "HTTPS", "API", "ARN", "CLI",
                            "SDK")
                        .contains(identifier))
            .distinct()
            .limit(8)
            .toList();
    List<Evidence> exact = new ArrayList<>();
    for (String identifier : identifiers) {
      parameters.put("identifier", identifier);
      exact.addAll(
          named.query(
              columns
                  + base
                  + " AND strpos(c.content,:identifier)>0 ORDER BY similarity DESC,c.id LIMIT 10",
              parameters,
              this::evidence));
    }
    // Rank fusion rewards agreement across retrieval methods without comparing incompatible
    // raw lexical and vector scores. Stable IDs break ties reproducibly.
    Map<String, Evidence> values = new HashMap<>();
    Map<String, Double> scores = new HashMap<>();
    for (List<Evidence> ranking : List.of(dense, lexical, exact.stream().distinct().toList())) {
      for (int i = 0; i < ranking.size(); i++) {
        Evidence e = ranking.get(i);
        values.put(e.id(), e);
        scores.merge(e.id(), 1.0 / (61 + i), Double::sum);
      }
    }
    return scores.entrySet().stream()
        .sorted(
            Map.Entry.<String, Double>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry::getKey))
        .map(e -> values.get(e.getKey()))
        .toList();
  }

  private Evidence evidence(ResultSet rs, int row) throws SQLException {
    return new Evidence(
        rs.getString("id"),
        rs.getString("source_id"),
        rs.getString("service"),
        rs.getString("title"),
        rs.getString("url"),
        rs.getString("heading"),
        rs.getString("anchor"),
        rs.getString("content"),
        rs.getTimestamp("fetched_at").toInstant(),
        rs.getDouble("similarity"));
  }

  @Override
  public Optional<Document> previousDocument(String id) {
    return jdbc
        .query(
            "SELECT d.* FROM document d JOIN active_corpus a ON a.generation_id=d.generation_id WHERE source_id=?",
            (rs, n) ->
                new Document(
                    id,
                    rs.getString("service"),
                    rs.getString("url"),
                    rs.getString("title"),
                    rs.getString("raw_hash"),
                    rs.getString("content_hash"),
                    rs.getString("snapshot_path"),
                    rs.getTimestamp("fetched_at").toInstant(),
                    rs.getString("etag"),
                    rs.getString("last_modified")),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<float[]> embedding(String inputHash, String profile) {
    return jdbc
        .query(
            "SELECT embedding::text FROM embedding_checkpoint WHERE input_hash=? AND profile=?",
            (rs, n) -> parseVector(rs.getString(1)),
            inputHash,
            profile)
        .stream()
        .findFirst();
  }

  @Override
  public void saveEmbedding(String inputHash, String profile, float[] vector) {
    jdbc.update(
        "INSERT INTO embedding_checkpoint VALUES (?,?,?::vector) ON CONFLICT DO NOTHING",
        inputHash,
        profile,
        vectorLiteral(vector));
  }

  @Override
  public void checked(String id, boolean success, String message) {
    jdbc.update(
        "INSERT INTO source_check(source_id,last_attempt,last_verified,error) VALUES (?,now(),CASE WHEN ? THEN now() END,?) ON CONFLICT(source_id) DO UPDATE SET last_attempt=now(),last_verified=CASE WHEN ? THEN now() ELSE source_check.last_verified END,error=EXCLUDED.error",
        id,
        success,
        message,
        success);
  }

  @Override
  public UUID beginJob(String manifestHash) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "UPDATE ingestion_job SET state='FAILED',finished_at=now(),error='Interrupted before completion' WHERE state='RUNNING'");
    jdbc.update(
        "INSERT INTO ingestion_job(id,manifest_hash,state) VALUES (?,?,'RUNNING')",
        id,
        manifestHash);
    return id;
  }

  @Override
  public void failJob(UUID job, String message) {
    jdbc.update(
        "UPDATE ingestion_job SET state='FAILED',finished_at=now(),error=? WHERE id=?",
        message,
        job);
  }

  /**
   * Publishes a full snapshot transactionally; identical fingerprints preserve the active
   * generation.
   */
  @Override
  public boolean publish(
      UUID job, String manifestHash, String profile, List<Document> documents, List<Chunk> chunks) {
    String fingerprint =
        Hashes.sha256(
            profile
                + manifestHash
                + documents.stream()
                    .sorted(Comparator.comparing(Document::sourceId))
                    .map(d -> d.sourceId() + d.contentHash())
                    .toList());
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              // Serialize publication at the active pointer. Inserts and pointer changes roll back
              // together if any chunk or document fails to persist.
              jdbc.queryForObject("SELECT singleton FROM active_corpus FOR UPDATE", Boolean.class);
              List<String> current =
                  jdbc.queryForList(
                      "SELECT g.fingerprint FROM corpus_generation g JOIN active_corpus a ON a.generation_id=g.id",
                      String.class);
              if (!current.isEmpty() && current.getFirst().equals(fingerprint)) {
                noChange(job);
                return false;
              }
              UUID generation = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO corpus_generation(id,profile,manifest_hash,fingerprint,state) VALUES (?,?,?,?,'READY')",
                  generation,
                  profile,
                  manifestHash,
                  fingerprint);
              for (Document d : documents) {
                jdbc.update(
                    "INSERT INTO document(generation_id,source_id,service,url,title,raw_hash,content_hash,snapshot_path,fetched_at,etag,last_modified) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    generation,
                    d.sourceId(),
                    d.service(),
                    d.url(),
                    d.title(),
                    d.rawHash(),
                    d.contentHash(),
                    d.snapshotPath(),
                    java.sql.Timestamp.from(d.fetchedAt()),
                    d.etag(),
                    d.lastModified());
              }
              jdbc.batchUpdate(
                  "INSERT INTO chunk(generation_id,id,source_id,heading,anchor,ordinal,content,embedding_input,input_hash,embedding) VALUES (?,?,?,?,?,?,?,?,?,?::vector)",
                  chunks,
                  100,
                  (ps, c) -> {
                    ps.setObject(1, generation);
                    ps.setString(2, c.id());
                    ps.setString(3, c.sourceId());
                    ps.setString(4, c.heading());
                    ps.setString(5, c.anchor());
                    ps.setInt(6, c.ordinal());
                    ps.setString(7, c.text());
                    ps.setString(8, c.embeddingInput());
                    ps.setString(9, c.inputHash());
                    ps.setString(10, vectorLiteral(c.vector()));
                  });
              jdbc.update(
                  "UPDATE active_corpus SET generation_id=?,last_complete_check=now()", generation);
              jdbc.update(
                  "UPDATE ingestion_job SET state='SUCCEEDED',finished_at=now() WHERE id=?", job);
              return true;
            }));
  }

  @Override
  public void noChange(UUID job) {
    jdbc.update("UPDATE active_corpus SET last_complete_check=now()");
    jdbc.update("UPDATE ingestion_job SET state='SUCCEEDED',finished_at=now() WHERE id=?", job);
  }

  @Override
  public void revoke(String sourceId) {
    transaction.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO revoked_source(source_id) VALUES (?) ON CONFLICT DO NOTHING", sourceId);
          jdbc.update("UPDATE active_corpus SET policy_epoch=policy_epoch+1");
        });
  }

  @Override
  public void rollback(UUID id) {
    transaction.executeWithoutResult(
        status -> {
          Generation current = active();
          Integer count =
              jdbc.queryForObject(
                  "SELECT count(*) FROM corpus_generation WHERE id=? AND profile=? AND state='READY'",
                  Integer.class,
                  id,
                  current.profile());
          if (count == null || count != 1)
            throw new SupportException(
                "INVALID_GENERATION", 400, "Rollback requires a retained, compatible generation.");
          jdbc.update(
              "UPDATE active_corpus SET generation_id=?,policy_epoch=policy_epoch+1,last_complete_check=NULL",
              id);
        });
  }

  @Override
  public Instant lastCompleteCheck() {
    return jdbc.query(
            "SELECT last_complete_check FROM active_corpus",
            (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
        .getFirst();
  }

  @Override
  public Instant nextRefreshAttempt() {
    return jdbc.query(
            "SELECT next_refresh_attempt FROM active_corpus",
            (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
        .getFirst();
  }

  @Override
  public void deferRefreshUntil(Instant time) {
    jdbc.update("UPDATE active_corpus SET next_refresh_attempt=?", java.sql.Timestamp.from(time));
  }

  /**
   * Validates a nonzero finite 768-dimensional vector before encoding it for a bound SQL parameter.
   */
  public static String vectorLiteral(float[] values) {
    if (values.length != 768) throw new IllegalArgumentException("Expected 768 dimensions");
    StringJoiner joiner = new StringJoiner(",", "[", "]");
    double norm = 0;
    for (float value : values) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite vector");
      joiner.add(Float.toString(value));
      norm += value * value;
    }
    if (norm == 0) throw new IllegalArgumentException("Zero vector");
    return joiner.toString();
  }

  private static float[] parseVector(String value) {
    String[] parts = value.substring(1, value.length() - 1).split(",");
    float[] result = new float[parts.length];
    for (int i = 0; i < parts.length; i++) result[i] = Float.parseFloat(parts[i]);
    return result;
  }
}
