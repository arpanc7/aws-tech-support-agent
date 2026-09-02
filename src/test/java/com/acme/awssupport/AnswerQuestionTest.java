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
 * <p>Checks bounded research, grounded synthesis, abstention, cache scope, revocation, and
 * concurrent request coalescing without depending on nondeterministic real-model output.
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
    when(model.embed(anyList(), any()))
        .thenAnswer(
            invocation -> {
              List<String> inputs = invocation.getArgument(0);
              return inputs.stream().map(ignored -> vector.clone()).toList();
            });
    when(model.decide(any(), anyList(), any()))
        .thenReturn(new ResearchDecision("ANSWER", List.of()));
    when(model.answer(any(), anyList(), any()))
        .thenReturn(
            new AnswerDraft(
                "ANSWER",
                List.of(
                    new DraftClaim(
                        "An explicit deny overrides an allow.", List.of(evidence.id())))));
    when(model.verify(any(), any(), anyList(), any())).thenReturn(true);
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
  void rendersSynthesizedClaimWithStoredCitationEvidence() {
    ChatResponse result = service.answer(question("How does explicit deny work?"));
    assertThat(result.status()).isEqualTo("ANSWERED");
    assertThat(result.answerMode()).isEqualTo("GROUNDED_SYNTHESIS");
    assertThat(result.claims().getFirst().text()).isEqualTo("An explicit deny overrides an allow.");
    assertThat(result.citations().getFirst().quote()).isEqualTo(evidence.text());
  }

  @Test
  void infersExplicitServiceWhenFilterIsEmpty() {
    service.answer(question("What is EC2?"));

    verify(repository).retrieve(any(), eq("What is EC2?"), eq("EC2"), any(), anyList());
  }

  @Test
  void retrievesEachNamedServiceIndependently() {
    service.answer(question("Compare EC2 with Lambda"));

    verify(repository)
        .retrieve(
            any(), eq("Compare EC2 with Lambda EC2 service overview"), eq("EC2"), any(), anyList());
    verify(repository)
        .retrieve(
            any(),
            eq("Compare EC2 with Lambda LAMBDA service overview"),
            eq("LAMBDA"),
            any(),
            anyList());
  }

  @Test
  void keepsQuestionsWithoutNamedServicesGloballyScoped() {
    service.answer(question("How do explicit denies work?"));

    verify(repository)
        .retrieve(any(), eq("How do explicit denies work?"), eq(""), any(), anyList());
  }

  @Test
  void explicitFilterOverridesMentionedService() {
    Question scoped = new Question("Can EC2 use it?", List.of(), new Filters("VPC", "", ""));

    assertThat(AnswerQuestion.retrievalServices(scoped)).containsExactly("VPC");
  }

  @Test
  void promotesCanonicalOverviewWithoutReorderingOtherEvidence() {
    Evidence overview =
        new Evidence(
            "ec2-overview",
            "ec2-concepts",
            "EC2",
            "What is Amazon EC2?",
            "https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/concepts.html",
            "What is Amazon EC2?",
            "concepts",
            "Amazon EC2 provides scalable computing capacity.",
            Instant.now(),
            .8);

    assertThat(AnswerQuestion.promoteOverview(List.of(evidence, overview)))
        .containsExactly(overview, evidence);
  }

  @Test
  void removesOneSidedTangentsFromGenericComparison() {
    Evidence ec2 =
        new Evidence(
            "ec2-overview",
            "ec2-concepts",
            "EC2",
            "What is Amazon EC2?",
            "https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/concepts.html",
            "What is Amazon EC2?",
            "concepts",
            "Amazon EC2 provides scalable virtual servers.",
            Instant.now(),
            .9);
    Evidence lambda =
        new Evidence(
            "lambda-overview",
            "lambda-welcome",
            "LAMBDA",
            "What is AWS Lambda?",
            "https://docs.aws.amazon.com/lambda/latest/dg/welcome.html",
            "What is AWS Lambda?",
            "welcome",
            "AWS Lambda is a serverless compute service.",
            Instant.now(),
            .9);
    DraftClaim comparison =
        new DraftClaim(
            "EC2 uses virtual servers; Lambda is serverless.", List.of(ec2.id(), lambda.id()));
    DraftClaim tangent = new DraftClaim("Lambda has functions.", List.of(lambda.id()));

    AnswerDraft retained =
        AnswerQuestion.retainGenericComparisonClaims(
            question("Compare EC2 with Lambda"),
            new AnswerDraft("ANSWER", List.of(comparison, tangent)),
            List.of(ec2, lambda));

    assertThat(retained.claims()).containsExactly(comparison);
  }

  @Test
  void exactHitsAvoidInferenceAndGetNewRequestMetadata() {
    Question q = question("How does explicit deny work?");
    ChatResponse first = service.answer(q);
    ChatResponse second = service.answer(q);
    assertThat(second.cacheDisposition()).isEqualTo("EXACT");
    assertThat(first.requestId()).isNotEqualTo(second.requestId());
    verify(model, times(1)).decide(any(), anyList(), any());
    verify(model, times(1)).answer(any(), anyList(), any());
    verify(model, times(1)).verify(any(), any(), anyList(), any());
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
    verify(model, never()).decide(any(), anyList(), any());
  }

  @Test
  void fabricatedSourceIsRejectedBeforeVerification() {
    when(model.answer(any(), anyList(), any()))
        .thenReturn(
            new AnswerDraft(
                "ANSWER", List.of(new DraftClaim("Invented claim", List.of("fabricated")))));
    assertThat(service.answer(question("Explain deny")).reason()).isEqualTo("VALIDATION_FAILED");
    verify(model, never()).verify(any(), any(), anyList(), any());
  }

  @Test
  void verifierUncertaintyAbstainsAndDoesNotCache() {
    when(model.verify(any(), any(), anyList(), any())).thenReturn(false);
    for (int i = 0; i < 2; i++)
      assertThat(service.answer(question("Explain deny")).claims()).isEmpty();
    verify(model, times(2)).verify(any(), any(), anyList(), any());
  }

  @Test
  void similarQuestionMustBeReverified() {
    service.answer(question("How do I allow access?"));
    when(model.verify(any(), any(), anyList(), any())).thenReturn(false);
    ChatResponse result = service.answer(question("How do I deny access?"));
    assertThat(result.status()).isEqualTo("INFORMATION_NOT_AVAILABLE");
    verify(model, atLeast(2)).decide(any(), anyList(), any());
  }

  @Test
  void performsAtMostOneAdditionalSearchRound() {
    Evidence endpoint =
        new Evidence(
            "span-endpoint",
            "s3-endpoint",
            "VPC",
            "Gateway endpoints",
            "https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints-s3.html",
            "Route tables",
            "route-tables",
            "Associate the gateway endpoint with the route tables used by your workloads.",
            Instant.now(),
            .91);
    when(model.decide(any(), anyList(), any()))
        .thenReturn(
            new ResearchDecision(
                "SEARCH_MORE",
                List.of(new SearchRequest("S3 gateway endpoint route tables", "VPC"))));
    when(repository.retrieve(
            any(), eq("S3 gateway endpoint route tables"), eq("VPC"), any(), anyList()))
        .thenReturn(List.of(endpoint));
    when(model.answer(any(), anyList(), any()))
        .thenReturn(
            new AnswerDraft(
                "ANSWER",
                List.of(
                    new DraftClaim(
                        "Associate the S3 gateway endpoint with the workload route tables.",
                        List.of(endpoint.id())))));

    ChatResponse result = service.answer(question("How should Lambda in a VPC reach S3?"));

    assertThat(result.status()).isEqualTo("ANSWERED");
    assertThat(result.citations()).extracting(Citation::spanId).containsExactly(endpoint.id());
    verify(model, times(1)).decide(any(), anyList(), any());
    verify(model, times(1)).answer(any(), anyList(), any());
    verify(model, times(2)).embed(anyList(), any());
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
  void invalidActionsAndDraftsAreRejected() {
    assertThat(AnswerQuestion.validDecision(new ResearchDecision("SEARCH_MORE", List.of())))
        .isFalse();
    assertThat(
            AnswerQuestion.validDecision(
                new ResearchDecision(
                    "SEARCH_MORE",
                    List.of(
                        new SearchRequest("endpoint", "VPC"),
                        new SearchRequest("endpoint", "VPC")))))
        .isFalse();
    assertThat(
            AnswerQuestion.validDraft(
                new AnswerDraft("ANSWER", List.of(new DraftClaim("Claim", List.of("fabricated")))),
                List.of(evidence)))
        .isFalse();
  }

  @Test
  void coalescesConcurrentIdenticalQuestions() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(model.decide(any(), anyList(), any()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
              return new ResearchDecision("ANSWER", List.of());
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.answer(question("Explain deny")));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> service.answer(question("Explain deny")));
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("ANSWERED");
      assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo("ANSWERED");
      verify(model, times(1)).decide(any(), anyList(), any());
    }
  }
}
