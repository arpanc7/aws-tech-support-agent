package com.acme.awssupport.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds downloaded bytes before buffering them, including responses with no Content-Length. */
public final class LimitedBodyHandler {
  private LimitedBodyHandler() {}

  /** Creates a handler that cancels the body stream if buffering would exceed the byte limit. */
  public static HttpResponse.BodyHandler<byte[]> bytes(int limit) {
    return info -> new Subscriber(limit);
  }

  /** Buffers delivered batches and rejects oversized bodies before copying excess bytes. */
  private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    Subscriber(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (bytes.size() + buffer.remaining() > limit) {
          subscription.cancel();
          result.completeExceptionally(new IllegalArgumentException("Response exceeds byte limit"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
