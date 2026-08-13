package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Minimal Ollama adapter. The target is deliberately restricted to this machine. */
@Component
@RequiredArgsConstructor
public class OllamaNovaAiGateway implements NovaAiGateway {
    private final NovaAiProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public String chat(ChatRequest request) {
        URI target = baseUri();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.model());
            body.put("messages", request.messages().stream()
                    .map(message -> Map.of("role", message.role(), "content", message.content()))
                    .toList());
            body.put("stream", false);
            body.put("think", false);
            body.put("options", Map.of(
                    "temperature", 0.2,
                    "num_predict", request.maxOutputTokens(),
                    "num_ctx", bounded(properties.getContextWindow(), 2_048, 131_072)));
            HttpRequest httpRequest = HttpRequest.newBuilder(target.resolve("/api/chat"))
                    .timeout(Duration.ofMillis(bounded(properties.getReadTimeoutMs(), 1_000, 300_000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) {
                throw unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String model = root.path("model").asText("");
            String role = root.path("message").path("role").asText("");
            String content = root.path("message").path("content").asText("").trim();
            if (!request.model().equals(model) || !"assistant".equals(role) || !root.path("done").asBoolean(false)
                    || content.isBlank() || content.length() > bounded(properties.getMaxOutputChars(), 256, 16_000)) {
                throw new BizException(502, "NOVA_AI_RESPONSE_INVALID");
            }
            return content;
        } catch (BizException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException ex) {
            throw unavailable();
        }
    }

    @Override
    public boolean available() {
        if (properties.getMode() != NovaAiProperties.Mode.OLLAMA_LOCAL) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri().resolve("/api/tags"))
                    .timeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 250, 10_000)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) return false;
            JsonNode models = objectMapper.readTree(response.body()).path("models");
            if (!models.isArray()) return false;
            for (JsonNode model : models) {
                if (properties.getModel().equals(model.path("name").asText())) return true;
            }
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 100, 10_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI baseUri() {
        return localBaseUri(properties.getBaseUrl());
    }

    private URI localBaseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!address.isLoopbackAddress()) throw new IllegalArgumentException();
            }
            String normalized = uri.toString().replaceAll("/+$", "") + "/";
            return URI.create(normalized);
        } catch (Exception ex) {
            throw new IllegalStateException("NOVA_AI_LOCAL_BASE_URL_INVALID");
        }
    }

    private BizException unavailable() {
        return new BizException(503, "NOVA_AI_UNAVAILABLE");
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
