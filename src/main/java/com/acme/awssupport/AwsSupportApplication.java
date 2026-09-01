package com.acme.awssupport;

import com.acme.awssupport.bootstrap.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstraps the local AWS support service and its Spring-managed adapters.
 *
 * <p>This is the executable entry point for both HTTP serving and operator commands. Spring wires
 * the application services to PostgreSQL, Ollama, and the document-processing adapters;
 * command-line mode is selected by startup arguments rather than a separate main class.
 */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
@EnableScheduling
public class AwsSupportApplication {
  /**
   * Starts Spring Boot; scripts/run selects HTTP serving or a non-web operator command via
   * arguments.
   */
  public static void main(String[] args) {
    SpringApplication.run(AwsSupportApplication.class, args);
  }
}
