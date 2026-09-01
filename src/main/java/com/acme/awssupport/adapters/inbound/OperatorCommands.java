package com.acme.awssupport.adapters.inbound;

import com.acme.awssupport.adapters.outbound.DatabaseLocks;
import com.acme.awssupport.application.IngestCorpus;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.ports.CorpusRepository;
import com.acme.awssupport.ports.LocalModel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs explicit maintenance and retrieval-diagnostic commands in the application context.
 *
 * <p>Commands include ingestion, refresh, revocation, rollback, and model-health recovery. The
 * context is closed after a command finishes. The retrieve/check diagnostic uses its own candidate
 * limits and does not reproduce the HTTP answer path's full evidence budgeting.
 */
@Component
public class OperatorCommands implements ApplicationRunner {
  private final IngestCorpus ingestion;
  private final RagProperties properties;
  private final CorpusRepository repository;
  private final ConfigurableApplicationContext context;
  private final JdbcTemplate jdbc;
  private final DatabaseLocks locks;
  private final LocalModel model;

  public OperatorCommands(
      IngestCorpus ingestion,
      RagProperties properties,
      CorpusRepository repository,
      ConfigurableApplicationContext context,
      JdbcTemplate jdbc,
      DatabaseLocks locks,
      LocalModel model) {
    this.ingestion = ingestion;
    this.properties = properties;
    this.repository = repository;
    this.context = context;
    this.jdbc = jdbc;
    this.locks = locks;
    this.model = model;
  }

  /**
   * Dispatches an explicitly requested operator command and closes the context when it finishes.
   */
  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!args.containsOption("command")) return;
    String command = args.getOptionValues("command").getFirst();
    try {
      switch (command) {
        case "ingest", "refresh" ->
            ingestion.run(
                args.containsOption("manifest")
                    ? Path.of(args.getOptionValues("manifest").getFirst())
                    : properties.manifest());
        case "validate-manifest" ->
            ingestion.validate(
                args.containsOption("manifest")
                    ? Path.of(args.getOptionValues("manifest").getFirst())
                    : properties.manifest());
        case "status" -> System.out.println(repository.status());
        case "retrieve" -> {
          String question = required(args, "question");
          String service = args.containsOption("service") ? required(args, "service") : "";
          var filters = new com.acme.awssupport.domain.Types.Filters(service, null, null);
          var query = new com.acme.awssupport.domain.Types.Question(question, List.of(), filters);
          var generation = repository.active();
          if (!generation.profile().equals(model.profile().embeddingProfile()))
            throw new IllegalStateException("Index profile mismatch");
          var vector =
              model
                  .embed(
                      List.of("search_query: " + query.question()),
                      new Deadline(Duration.ofSeconds(60)))
                  .getFirst();
          var evidence =
              repository.retrieve(
                  generation, query.question(), filters.service(), vector, List.of());
          System.out.println(
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .findAndRegisterModules()
                  .writeValueAsString(evidence.stream().limit(12).toList()));
          if (args.containsOption("check")) {
            var candidates = evidence.stream().limit(8).toList();
            var decision = model.decide(query, candidates, new Deadline(Duration.ofSeconds(60)));
            System.out.println(decision);
            if (decision.action().equals("ANSWER")) {
              var draft = model.answer(query, candidates, new Deadline(Duration.ofSeconds(60)));
              System.out.println(draft);
              if (com.acme.awssupport.application.AnswerQuestion.validDraft(draft, candidates))
                System.out.println(
                    "grounded="
                        + model.verify(
                            query, draft, candidates, new Deadline(Duration.ofSeconds(60))));
            } else if (decision.action().equals("SEARCH_MORE"))
              System.out.println(
                  "Additional searches are shown above; the retrieve diagnostic does not execute the agent round.");
          }
        }
        case "revoke" -> repository.revoke(required(args, "source"));
        case "rollback" -> repository.rollback(UUID.fromString(required(args, "generation")));
        case "model-reset" -> {
          if (!args.containsOption("runtime-restarted"))
            throw new IllegalArgumentException(
                "Restart Ollama first, then pass --runtime-restarted to acknowledge recovery");
          try (var lease =
              locks.acquire(DatabaseLocks.INFERENCE, new Deadline(Duration.ofSeconds(3)), false)) {
            model.profile();
            jdbc.update("UPDATE model_health SET healthy=true,reason='' WHERE singleton=true");
          }
        }
        default -> throw new IllegalArgumentException("Unknown command: " + command);
      }
    } finally {
      context.close();
    }
  }

  private static String required(ApplicationArguments args, String name) {
    if (!args.containsOption(name)) throw new IllegalArgumentException("Missing --" + name);
    return args.getOptionValues(name).getFirst();
  }
}
