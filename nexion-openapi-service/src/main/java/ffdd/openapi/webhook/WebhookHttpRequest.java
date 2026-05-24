package ffdd.openapi.webhook;

import java.util.Map;

public record WebhookHttpRequest(String url, Map<String, String> headers, String payload) {
}
