package com.acme.awssupport.domain;

import java.time.Duration;

/**
 * A shared, monotonic time budget for one operation and its dependent calls.
 *
 * <p>Uses {@link System#nanoTime()} so wall-clock changes do not extend the budget. Checking the
 * remaining time also detects thread interruption; each downstream call receives the remaining
 * budget instead of starting a fresh timeout.
 */
public final class Deadline {
  private final long end;

  /** Starts the shared budget at construction time. */
  public Deadline(Duration budget) {
    end = System.nanoTime() + budget.toNanos();
  }

  /** Returns the unspent budget or throws if elapsed or interrupted. */
  public Duration remaining() {
    long remaining = end - System.nanoTime();
    if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
      throw new SupportException(
          "DEADLINE_EXCEEDED", 504, "The request timed out. Please try again.");
    }
    return Duration.ofNanos(remaining);
  }

  /** Fails immediately if no time remains or the current thread was interrupted. */
  public void check() {
    remaining();
  }
}
