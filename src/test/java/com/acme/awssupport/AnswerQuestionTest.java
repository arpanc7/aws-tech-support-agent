package com.acme.awssupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.awssupport.application.AnswerQuestion;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/**
 * Exercises answer orchestration with controlled repository and model collaborators.
 *
 * <p>Checks excerpt-only rendering, abstention, cache scope, revocation, and concurrent request
 * coalescing without depending on nondeterministic real-model output.
 */
class AnswerQuestionTest {
  CorpusRepository repository;
  LocalModel model;
  TokenCounter tokens;
  AnswerQuestion service;
  Generation generation;
  Evidence evidence;

  static RagProperties properties() {
    return new RagProperties(
        "http://127.0.0.1:11434",
        "qwen3:4b",
        "nomic-embed-text:v1.5",
        Path.of("data"),
        Path.of("config/sources.json"),
        Duration.ofSeconds(60),
        Duration.ofHours(24),
        false,
        8192,
        4500,
        20000,
        .4,
        .94);
  }

  @BeforeEach
  void setup() {
    repository = mock(CorpusRepository.class);
    model = mock(LocalModel.class);
    tokens = mock(TokenCounter.class);
    ModelProfile profile = new ModelProfile("embed", "chat", "tokens", "prompts");
    generation =
        new Generation(UUID.randomUUID(), profile.embeddingProfile(), "manifest", Instant.now(), 0);
    evidence =
        new Evidence(
            "span-one",
            "iam-deny",
            "IAM",
            "Policy evaluation",
            "https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_evaluation-logic.html",
            "Explicit deny",
            "deny",
            "An explicit deny overrides an allow.",
            Instant.now(),
            .9);
    when(repository.active()).thenReturn(generation);
    when(repository.isValid(any(), anyList())).thenReturn(true);
    when(repository.retrieve(any(), anyString(), anyString(), any(), anyList()))
        .thenReturn(List.of(evidence));
    when(model.profile()).thenReturn(profile);
    float[] vector = new float[768];
    vector[0] = 1;
    when(model.embed(anyList(), any())).thenReturn(List.of(vector));
    when(model.select(any(), anyList(), any()))
        .thenReturn(new Selection("ANSWERABLE", List.of(evidence.id())));
    when(model.verify(any(), anyList(), any())).thenReturn(true);
    when(tokens.generationTokens(anyString())).thenReturn(100);
    service =
        new AnswerQuestion(
            repository,
            model,
            tokens,
            properties(),
            JsonMapper.builder().findAndAddModules().build(),
            new SimpleMeterRegistry());
  }

  Question question(String text) {
    return new Question(text, List.of(), null);
  }

  @Test
  void rendersOnlyStoredSourceText() {
    ChatResponse result = service.answer(question("How does explicit deny work?"));
    assertThat(result.status()).isEqualTo("ANSWERED");
    assertThat(result.claims().getFirst().text()).isEqualTo(evidence.text());
    assertThat(result.citations().getFirst().quote()).isEqualTo(evidence.text());
  }

  @Test
  void exactHitsAvoidInferenceAndGetNewRequestMetadata() {
    Question q = question("How does explicit deny work?");
    ChatResponse first = service.answer(q);
    ChatResponse second = service.answer(q);
    assertThat(second.cacheDisposition()).isEqualTo("EXACT");
    assertThat(first.requestId()).isNotEqualTo(second.requestId());
    verify(model, times(1)).select(any(), anyList(), any());
    verify(model, times(1)).embed(anyList(), any());
  }

  @Test
  void modelKnowledgeDoesNotReplaceMissingEvidence() {
    when(repository.retrieve(any(), anyString(), anyString(), any(), anyList()))
        .thenReturn(List.of());
    ChatResponse result = service.answer(question("Explain an unsupported service"));
    assertThat(result.status()).isEqualTo("INFORMATION_NOT_AVAILABLE");
    assertThat(result.message())
        .isEqualTo("Information is not available in the local documentation.");
    verify(model, never()).select(any(), anyList(), any());
  }

  @Test
  void fabricatedSourceIsRejectedBeforeVerification() {
    when(model.select(any(), anyList(), any()))
        .thenReturn(new Selection("ANSWERABLE", List.of("fabricated")));
    assertThat(service.answer(question("Explain deny")).reason()).isEqualTo("VALIDATION_FAILED");
    verify(model, never()).verify(any(), anyList(), any());
  }

  @Test
  void verifierUncertaintyAbstainsAndDoesNotCache() {
    when(model.verify(any(), anyList(), any())).thenReturn(false);
    for (int i = 0; i < 2; i++)
      assertThat(service.answer(question("Explain deny")).claims()).isEmpty();
    verify(model, times(2)).verify(any(), anyList(), any());
  }

  @Test
  void similarQuestionMustBeReverified() {
    service.answer(question("How do I allow access?"));
    when(model.verify(any(), anyList(), any())).thenReturn(false);
    ChatResponse result = service.answer(question("How do I deny access?"));
    assertThat(result.status()).isEqualTo("INFORMATION_NOT_AVAILABLE");
    verify(model, atLeast(2)).select(any(), anyList(), any());
  }

  @Test
  void changedConversationCannotHitExactAnswerCache() {
    service.answer(new Question("What about deny?", List.of("S3 permissions"), null));
    assertThat(
            service
                .answer(new Question("What about deny?", List.of("IAM permissions"), null))
                .cacheDisposition())
        .isEqualTo("MISS");
  }

  @Test
  void revokedCacheAnswerIsNotServed() {
    Question q = question("Explain deny");
    service.answer(q);
    when(repository.isValid(any(), anyList())).thenReturn(false);
    assertThat(service.answer(q).claims()).isEmpty();
  }

  @Test
  void explicitUnknownVersionRequiresClarification() {
    assertThat(
            service
                .answer(
                    new Question("Explain permissions", List.of(), new Filters("IAM", "", "2099")))
                .status())
        .isEqualTo("CLARIFICATION_REQUIRED");
    verify(model, never()).embed(anyList(), any());
  }

  @Test
  void duplicateAndEmptySelectionsAreInvalid() {
    assertThat(
            AnswerQuestion.validateSelection(
                new Selection("ANSWERABLE", List.of()), List.of(evidence)))
        .isEmpty();
    assertThat(
            AnswerQuestion.validateSelection(
                new Selection("ANSWERABLE", List.of("span-one", "span-one")), List.of(evidence)))
        .isEmpty();
  }

  @Test
  void coalescesConcurrentIdenticalQuestions() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(model.select(any(), anyList(), any()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
              return new Selection("ANSWERABLE", List.of(evidence.id()));
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.answer(question("Explain deny")));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> service.answer(question("Explain deny")));
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("ANSWERED");
      assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo("ANSWERED");
      verify(model, times(1)).select(any(), anyList(), any());
    }
  }
}
