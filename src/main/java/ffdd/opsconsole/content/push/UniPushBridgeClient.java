package ffdd.opsconsole.content.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Java backend -> signed uniCloud URL function -> official uni-cloud-push extension. */
@Component
public class UniPushBridgeClient {
    public record Result(String status, String code, String taskId) { }
    private final ObjectMapper json;
    private final Environment env;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public UniPushBridgeClient(ObjectMapper json, Environment env) {
        this(json, env, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    UniPushBridgeClient(ObjectMapper json, Environment env, HttpClient http) {
        this.json = json; this.env = env; this.http = http;
    }

    public String configurationState() {
        if (!env.getProperty("native-push.enabled", Boolean.class, false)) return "DISABLED";
        String url = env.getProperty("native-push.bridge-url", "");
        String secret = env.getProperty("native-push.bridge-secret", "");
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || secret.length() < 32) return "MISSING_OR_INVALID_CONFIG";
        } catch (IllegalArgumentException invalid) { return "MISSING_OR_INVALID_CONFIG"; }
        return "CONFIGURED";
    }

    public Result send(long deliveryId, long notificationId, String cid, String language) {
        String config = configurationState();
        if (!"CONFIGURED".equals(config)) return new Result("BLOCKED", config, null);
        try {
            String body = json.writeValueAsString(Map.of("requestId", String.format("n1%030d", deliveryId),
                    "notificationId", String.valueOf(notificationId), "clientId", cid,
                    "language", language == null ? "en" : language));
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(env.getRequiredProperty("native-push.bridge-url")))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-Push-Timestamp", timestamp).header("X-Push-Nonce", nonce)
                    .header("X-Push-Signature", signature(env.getRequiredProperty("native-push.bridge-secret"), timestamp, nonce, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<byte[]> response = http.send(request, ignored -> new LimitedResponse());
            {
                byte[] bytes = response.body();
                if (response.statusCode() != 200) return new Result("RETRY", "BRIDGE_HTTP_" + response.statusCode(), null);
                var data = json.readTree(bytes);
                if (!data.isObject() || !data.path("requestId").asText().equals(String.format("n1%030d", deliveryId)))
                    return new Result("RETRY", "BRIDGE_INVALID_RESPONSE", null);
                String status = data.path("status").asText();
                if (!java.util.Set.of("ACCEPTED", "INVALID_CID", "RETRY", "UNKNOWN").contains(status))
                    return new Result("RETRY", "BRIDGE_INVALID_RESPONSE", null);
                String task = data.path("taskId").asText("");
                if (task.length() > 128 || ("ACCEPTED".equals(status) && task.isBlank())) return new Result("UNKNOWN", "BRIDGE_MISSING_RECEIPT", null);
                return new Result(status, "BRIDGE_" + status, task.isBlank() ? null : task);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result("RETRY", "BRIDGE_INTERRUPTED", null);
        } catch (Exception failed) {
            // Never log a CID, HMAC, request body, token or a provider exception containing them.
            return new Result("RETRY", "BRIDGE_UNAVAILABLE", null);
        }
    }

    static String signature(String secret, String timestamp, String nonce, String body) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + nonce + "\n" + digest).getBytes(StandardCharsets.UTF_8)));
    }

    /** Cancel oversized responses while receiving, so HTTP timeout covers body completion without an unbounded stream read. */
    static final class LimitedResponse implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> complete = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return complete; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 8192) {
                    subscription.cancel(); complete.completeExceptionally(new java.io.IOException("BRIDGE_RESPONSE_TOO_LARGE")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
        }
        public void onError(Throwable error) { complete.completeExceptionally(error); }
        public void onComplete() { complete.complete(bytes.toByteArray()); }
    }
}
