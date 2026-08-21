package ffdd.opsconsole.developer.application;

import java.util.Map;

/** Narrow seam around network I/O; deterministic tests inject this instead of using the internet. */
@FunctionalInterface
public interface DeveloperWebhookDeliveryTransport {
    Response send(String url, Map<String, String> headers, String rawBody) throws Exception;

    record Response(int statusCode, String responseBody) { }
}
