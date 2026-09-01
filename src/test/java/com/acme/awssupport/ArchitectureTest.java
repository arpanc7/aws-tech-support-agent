package com.acme.awssupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Enforces dependency boundaries so domain/port types remain independent of adapters and
 * frameworks.
 */
class ArchitectureTest {
  @Test
  void applicationDependsOnPortsRatherThanStorageAdapters() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..adapters..")
        .check(new ClassFileImporter().importPackages("com.acme.awssupport"));
  }

  @Test
  void domainAndPortsDoNotDependOnFrameworkOrAdapters() {
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..ports..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "..adapters..",
            "com.fasterxml..",
            "dev.langchain4j..",
            "com.github.benmanes..")
        .check(new ClassFileImporter().importPackages("com.acme.awssupport"));
  }
}
