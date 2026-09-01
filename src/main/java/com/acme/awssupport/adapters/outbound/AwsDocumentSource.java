package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.DocumentSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Retrieves allowlisted AWS HTML pages or explicitly configured local imports.
 *
 * <p>Restricts URL shape, rejects local DNS addresses and redirects, caps response size, and
 * retries transient HTTP failures. Conditional requests reuse content-addressed snapshots only
 * after their checksums match. Imported files must resolve inside the configured imports directory.
 */
@Component
public class AwsDocumentSource implements DocumentSource {
  private static final int MAX_BYTES = 5 * 1024 * 1024;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Path dataDirectory;

  public AwsDocumentSource(RagProperties properties) {
    dataDirectory = properties.dataDir().toAbsolutePath().normalize();
  }

  /**
   * Accepts only canonical HTTPS AWS HTML URLs without redirects, encoded paths, or query strings.
   */
  public static URI validateUrl(String url) {
    URI uri = URI.create(url);
    if (!"https".equals(uri.getScheme())
        || !"docs.aws.amazon.com".equals(uri.getHost())
        || uri.getUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().endsWith(".html")
        || !uri.normalize().equals(uri)
        || uri.getRawPath().contains("%")) {
      throw new IllegalArgumentException(
          "Only canonical HTTPS AWS documentation HTML pages are allowed");
    }
    return uri;
  }

  @Override
  public Download fetch(Source source, Optional<Document> previous) {
    URI uri = validateUrl(source.url());
    try {
      if (source.localFile() != null && !source.localFile().isBlank()) {
        Path imports = dataDirectory.resolve("imports");
        Path file = imports.resolve(source.localFile()).normalize();
        if (!file.startsWith(imports) || !file.toRealPath().startsWith(imports.toRealPath()))
          throw new IllegalArgumentException("Local snapshots must be under data/imports");
        if (Files.size(file) > MAX_BYTES)
          throw new IllegalArgumentException("Local document too large");
        return new Download(Files.readAllBytes(file), null, null);
      }
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress())
          throw new IllegalArgumentException("AWS hostname resolved to a disallowed address");
      }
      for (int attempt = 0; attempt < 3; attempt++) {
        HttpRequest.Builder request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "AwsSupportLocalRag/0.1 (documentation snapshot)")
                .GET();
        Optional<Document> cached =
            previous.filter(d -> d.url().equals(source.url()) && Files.isRegularFile(snapshot(d)));
        cached.ifPresent(
            d -> {
              if (d.etag() != null && !d.etag().isBlank())
                request.header("If-None-Match", d.etag());
              else if (d.lastModified() != null && !d.lastModified().isBlank())
                request.header("If-Modified-Since", d.lastModified());
            });
        HttpResponse<byte[]> response =
            client.send(request.build(), LimitedBodyHandler.bytes(MAX_BYTES));
        if (response.statusCode() == 304 && cached.isPresent()) {
          Document d = cached.get();
          byte[] bytes = Files.readAllBytes(snapshot(d));
          if (!Hashes.sha256(bytes).equals(d.rawHash()))
            throw new IllegalArgumentException("Snapshot integrity check failed");
          return new Download(bytes, d.etag(), d.lastModified());
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
          if (attempt < 2) {
            Thread.sleep(
                500L * (1L << attempt)
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(150));
            continue;
          }
        }
        // No redirects are followed: a moved source must be reviewed in the manifest.
        if (response.statusCode() != 200)
          throw new IllegalArgumentException("Documentation HTTP status " + response.statusCode());
        if (!response.headers().firstValue("Content-Type").orElse("").contains("text/html"))
          throw new IllegalArgumentException("Expected an HTML document");
        return new Download(
            response.body(),
            response.headers().firstValue("ETag").orElse(null),
            response.headers().firstValue("Last-Modified").orElse(null));
      }
      throw new IllegalArgumentException("Download attempts exhausted");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Download interrupted", error);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Document download failed", error);
    }
  }

  /**
   * Resolves a content-addressed snapshot under the current data directory, allowing portable
   * restores.
   */
  private Path snapshot(Document document) {
    if (!document.rawHash().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid snapshot hash");
    return dataDirectory.resolve("snapshots").resolve(document.rawHash() + ".html");
  }
}
