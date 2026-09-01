package com.acme.awssupport;

import static org.assertj.core.api.Assertions.*;

import com.acme.awssupport.adapters.outbound.*;
import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.Types.*;
import com.zaxxer.hikari.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies publication, retrieval, revocation, and advisory locks against real PostgreSQL/pgvector.
 *
 * <p>Uses an isolated schema in an explicitly configured test database, or a disposable
 * Testcontainers database when no URL is supplied. Cleanup is scoped to test-owned resources.
 */
class PostgresCorpusIT {
  static HikariDataSource dataSource;
  static PostgreSQLContainer<?> container;
  static JdbcTemplate jdbc;
  static String schema;
  PostgresCorpusRepository repository;

  @BeforeAll
  static void start() {
    String url = System.getenv("RAG_TEST_DB_URL");
    String username = System.getenv().getOrDefault("RAG_TEST_DB_USER", "aws_support");
    String password = System.getenv("RAG_DB_PASSWORD");
    if (url == null) {
      container =
          new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f")
                  .asCompatibleSubstituteFor("postgres"));
      container.start();
      url = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(5);
    dataSource = new HikariDataSource(config);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    schema = "test_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE SCHEMA " + schema);
    dataSource.close();
    config.setConnectionInitSql("SET search_path TO " + schema + ",public");
    dataSource = new HikariDataSource(config);
    jdbc = new JdbcTemplate(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) {
      jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      dataSource.close();
    }
    if (container != null) container.stop();
  }

  @BeforeEach
  void reset() {
    jdbc.execute(
        "TRUNCATE chunk,document,corpus_generation,active_corpus,ingestion_job,embedding_checkpoint,revoked_source,source_check CASCADE");
    jdbc.execute("INSERT INTO active_corpus(singleton) VALUES(true)");
    repository =
        new PostgresCorpusRepository(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  float[] vector(int dimension) {
    float[] v = new float[768];
    v[dimension] = 1;
    return v;
  }

  Document document(String id, String service) {
    return new Document(
        id,
        service,
        "https://docs.aws.amazon.com/" + id + ".html",
        "Document " + id,
        "raw",
        "hash-" + id,
        "/tmp/test-snapshot",
        Instant.now(),
        null,
        null);
  }

  Chunk chunk(String id, String source, int dimension) {
    return new Chunk(
        id,
        source,
        "Permissions",
        "permissions",
        0,
        "Explicit deny blocks s3:GetObject permissions.",
        "search_document: permissions",
        "input-" + id,
        vector(dimension));
  }

  UUID publish(String manifest, List<Document> docs, List<Chunk> chunks) {
    UUID job = repository.beginJob(manifest);
    repository.publish(job, manifest, "test-profile", docs, chunks);
    return repository.active().id();
  }

  @Test
  void publishesAndRetrievesWithServiceIsolation() {
    publish(
        "one",
        List.of(document("iam", "IAM"), document("s3", "S3")),
        List.of(chunk("c1", "iam", 0), chunk("c2", "s3", 1)));
    var hits =
        repository.retrieve(repository.active(), "s3:GetObject deny", "S3", vector(0), List.of());
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().service()).isEqualTo("S3");
    assertThat(repository.status().documents()).isEqualTo(2);
  }

  @Test
  void unchangedPublicationKeepsGenerationAndEmbeddingCheckpoint() {
    UUID generation =
        publish("one", List.of(document("iam", "IAM")), List.of(chunk("c1", "iam", 0)));
    UUID job = repository.beginJob("one");
    assertThat(
            repository.publish(
                job,
                "one",
                "test-profile",
                List.of(document("iam", "IAM")),
                List.of(chunk("c1", "iam", 0))))
        .isFalse();
    assertThat(repository.active().id()).isEqualTo(generation);
    repository.saveEmbedding("same", "profile-a", vector(0));
    assertThat(repository.embedding("same", "profile-a")).isPresent();
    assertThat(repository.embedding("same", "profile-b")).isEmpty();
  }

  @Test
  void failedPublicationRollsBackAndOldGenerationRemainsQueryable() {
    UUID old = publish("one", List.of(document("iam", "IAM")), List.of(chunk("c1", "iam", 0)));
    UUID job = repository.beginJob("broken");
    assertThatThrownBy(
            () ->
                repository.publish(
                    job,
                    "broken",
                    "test-profile",
                    List.of(document("s3", "S3")),
                    List.of(chunk("c2", "missing", 0))))
        .isInstanceOf(RuntimeException.class);
    assertThat(repository.active().id()).isEqualTo(old);
    assertThat(repository.retrieve(repository.active(), "deny", "", vector(0), List.of()))
        .hasSize(1);
  }

  @Test
  void revocationInvalidatesAlreadyPinnedGeneration() {
    publish("one", List.of(document("iam", "IAM")), List.of(chunk("c1", "iam", 0)));
    Generation pinned = repository.active();
    assertThat(repository.isValid(pinned, List.of("iam"))).isTrue();
    repository.revoke("iam");
    assertThat(repository.isValid(pinned, List.of("iam"))).isFalse();
    assertThat(repository.retrieve(repository.active(), "deny", "", vector(0), List.of()))
        .isEmpty();
  }

  @Test
  void advisoryLocksExcludeConcurrentWriters() {
    DatabaseLocks locks = new DatabaseLocks(dataSource);
    try (var lease =
        locks.acquire(DatabaseLocks.INGESTION, new Deadline(Duration.ofSeconds(2)), false)) {
      assertThatThrownBy(
              () ->
                  locks.acquire(
                      DatabaseLocks.INGESTION, new Deadline(Duration.ofSeconds(2)), false))
          .isInstanceOf(RuntimeException.class);
    }
    try (var lease =
        locks.acquire(DatabaseLocks.INGESTION, new Deadline(Duration.ofSeconds(2)), false)) {
      assertThat(lease).isNotNull();
    }
  }

  @Test
  void commonAwsAcronymCannotDisplaceTheRelevantTimeoutPassage() {
    List<Chunk> chunks = new ArrayList<>();
    chunks.add(
        new Chunk(
            "answer",
            "lambda",
            "Configure Lambda function timeout",
            "",
            0,
            "Timeout is the maximum amount of time a Lambda function can run, up to 900 seconds.",
            "answer",
            "answer",
            vector(0)));
    for (int i = 0; i < 12; i++) {
      float[] nearby = vector(0);
      nearby[1] = .2f;
      chunks.add(
          new Chunk(
              "noise" + i,
              "lambda",
              "General AWS concepts",
              "",
              i + 1,
              "AWS provides information about cloud services and background concepts.",
              "noise" + i,
              "noise" + i,
              nearby));
    }
    publish("timeout-regression", List.of(document("lambda", "LAMBDA")), chunks);
    var results =
        repository.retrieve(
            repository.active(),
            "What is the maximum timeout for an AWS Lambda function?",
            "LAMBDA",
            vector(0),
            List.of());
    assertThat(results.getFirst().id()).isEqualTo("answer");
  }
}
