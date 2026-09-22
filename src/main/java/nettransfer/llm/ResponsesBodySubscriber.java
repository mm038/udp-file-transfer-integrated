package nettransfer.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Consume the complete body with a fixed memory bound; the client separately bounds its future. */
final class ResponsesBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private static final int MAX_BYTES = 1_048_576;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

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
            if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(new BodyLimitException());
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }

    static final class BodyLimitException extends IOException {
        private BodyLimitException() {
            super("Response body exceeds the configured limit.");
        }
    }
}
