package com.acme.awssupport.adapters.inbound;

import com.acme.awssupport.application.AnswerQuestion;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.CorpusRepository;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP entry point for question answering and corpus status under {@code /api/v1}.
 *
 * <p>Delegates policy and inference orchestration to the application layer. Browser request checks
 * run in {@link LocalSecurityFilter} before these endpoints are invoked.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {
  private final AnswerQuestion answers;
  private final CorpusRepository repository;

  public ChatController(AnswerQuestion answers, CorpusRepository repository) {
    this.answers = answers;
    this.repository = repository;
  }

  /**
   * Delegates a validated question to the grounded-answer use case; does not invoke models
   * directly.
   */
  @PostMapping("/chat")
  public ChatResponse chat(@RequestBody Question question) {
    return answers.answer(question);
  }

  /** Returns active corpus coverage and freshness for the local UI. */
  @GetMapping("/corpus")
  public CorpusStatus corpus() {
    return repository.status();
  }
}
