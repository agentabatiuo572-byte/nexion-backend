package ffdd.openapi.webhook;

public interface WebhookHttpClient {
    WebhookHttpResponse post(WebhookHttpRequest request);
}
