package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Deadline;

/** Cross-process coordination boundary, independent of its persistence mechanism. */
public interface WorkCoordinator {
  long INGESTION = 492311;
  long INFERENCE = 492312;

  /**
   * Acquires exclusive work ownership, failing immediately when busy or waiting within the
   * deadline.
   */
  Lease acquire(long key, Deadline deadline, boolean wait);

  /** Ownership handle that must be closed, normally through try-with-resources. */
  interface Lease extends AutoCloseable {
    /** Releases work ownership when the protected operation finishes. */
    @Override
    void close();
  }
}
