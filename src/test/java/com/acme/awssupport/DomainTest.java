package com.acme.awssupport;

import static org.assertj.core.api.Assertions.*;

import com.acme.awssupport.adapters.outbound.PostgresCorpusRepository;
import com.acme.awssupport.domain.*;
import com.acme.awssupport.domain.Types.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Checks request normalization, bounded inputs, vector validation, and monotonic deadline behavior.
 */
class DomainTest {
  @Test
  void preservesCasePunctuationAndNegation() {
    var q = new Question("  Do NOT allow s3:GetObject\r\nfor this ARN?  ", null, null);
    assertThat(q.question()).isEqualTo("Do NOT allow s3:GetObject\nfor this ARN?");
  }

  @Test
  void rejectsMalformedQuestionAndHistory() {
    assertThatThrownBy(() -> new Question(" ", null, null)).isInstanceOf(SupportException.class);
    assertThatThrownBy(() -> new Question("a".repeat(4001), null, null))
        .isInstanceOf(SupportException.class);
    assertThatThrownBy(() -> new Question("a", List.of("1", "2", "3", "4"), null))
        .isInstanceOf(SupportException.class);
  }

  @Test
  void validatesVectorsBeforeSql() {
    assertThatThrownBy(() -> PostgresCorpusRepository.vectorLiteral(new float[768]))
        .isInstanceOf(IllegalArgumentException.class);
    float[] vector = new float[768];
    vector[0] = Float.NaN;
    assertThatThrownBy(() -> PostgresCorpusRepository.vectorLiteral(vector))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostgresCorpusRepository.vectorLiteral(new float[3]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deadlineExpiresWithoutResetting() {
    assertThatThrownBy(() -> new Deadline(Duration.ZERO).check())
        .isInstanceOf(SupportException.class);
  }
}
