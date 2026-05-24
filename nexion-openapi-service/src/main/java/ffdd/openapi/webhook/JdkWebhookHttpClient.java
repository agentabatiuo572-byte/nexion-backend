package ffdd.openapi.webhook;

import ffdd.common.exception.BizException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkWebhookHttpClient implements WebhookHttpClient {
    private final HttpClient httpClient;
    private final Duration readTimeout;

    public JdkWebhookHttpClient(
            @Value("${nexion.openapi.webhook.delivery.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${nexion.openapi.webhook.delivery.read-timeout-ms:5000}") int readTimeoutMs) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, connectTimeoutMs)))
                .build();
        this.readTimeout = Duration.ofMillis(Math.max(100, readTimeoutMs));
    }

    @Override
    public WebhookHttpResponse post(WebhookHttpRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(readTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(request.payload()));
            request.headers().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new WebhookHttpResponse(response.statusCode(), response.body());
        } catch (Exception ex) {
            throw new BizException(503, "Webhook delivery failed");
        }
    }
}
