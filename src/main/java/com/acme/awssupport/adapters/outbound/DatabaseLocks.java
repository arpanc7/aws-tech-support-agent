package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.ports.WorkCoordinator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Session locks serialize CLI and server processes without holding an open transaction. */
@Component
public class DatabaseLocks implements WorkCoordinator {
  private final DataSource dataSource;

  public DatabaseLocks(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Acquires a PostgreSQL session advisory lock on a dedicated connection within the given budget.
   */
  public Lease acquire(long key, Deadline deadline, boolean wait) {
    try {
      Connection connection = dataSource.getConnection();
      try {
        while (true) {
          deadline.check();
          try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (var result = statement.executeQuery()) {
              result.next();
              if (result.getBoolean(1)) return new Lease(connection, key);
            }
          }
          if (!wait)
            throw new SupportException(
                "JOB_ALREADY_RUNNING", 409, "Another ingestion job is running.");
          TimeUnit.MILLISECONDS.sleep(100);
        }
      } catch (Exception error) {
        connection.close();
        throw error;
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new SupportException("DEADLINE_EXCEEDED", 504, "Request interrupted.");
    } catch (SQLException error) {
      throw new SupportException("DATABASE_UNAVAILABLE", 503, "Database unavailable.");
    }
  }

  /** Owns the session holding an advisory lock; close unlocks before returning the connection. */
  public static final class Lease implements WorkCoordinator.Lease {
    private final Connection connection;
    private final long key;

    Lease(Connection connection, long key) {
      this.connection = connection;
      this.key = key;
    }

    @Override
    public void close() {
      try {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          statement.setLong(1, key);
          statement.execute();
        }
      } catch (SQLException error) {
        try {
          // A pooled session must not be reused while it might still own the advisory lock.
          connection.abort(Runnable::run);
        } catch (SQLException ignored) {
          /* broken connection */
        }
      } finally {
        try {
          connection.close();
        } catch (SQLException ignored) {
          /* closed */
        }
      }
    }
  }
}
