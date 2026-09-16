package ffdd.opsconsole.finance.hdpay;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Limit accumulation before allocation, and complete only after the entire body arrived. */
final class HdPayPayoutBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) { value.cancel(); return; }
        subscription = value; value.request(1);
    }
    @Override public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) return;
        for (ByteBuffer item : items) {
            if (item.remaining() > 65_536 - bytes.size()) {
                subscription.cancel(); body.completeExceptionally(new IOException("BODY_TOO_LARGE")); return;
            }
            byte[] chunk = new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
