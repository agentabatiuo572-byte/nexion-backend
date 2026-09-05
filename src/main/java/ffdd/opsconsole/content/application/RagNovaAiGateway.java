package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Local Nova adapter backed by the authoritative Qdrant + Gemma RAG service. */
@Component
public class RagNovaAiGateway implements NovaAiGateway {
    private final NovaAiProperties properties;
    private final ObjectMapper objectMapper;
    private final SupportKnowledgeRepository knowledgeRepository;

    @Autowired
    public RagNovaAiGateway(NovaAiProperties properties, ObjectMapper objectMapper,
                            SupportKnowledgeRepository knowledgeRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.knowledgeRepository = knowledgeRepository;
    }

    public RagNovaAiGateway(NovaAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    @Override
    public String chat(ChatRequest request) {
        try {
            URI target = baseUri();
            int currentIndex = request.messages().size() - 1;
            if (currentIndex < 0 || !"user".equals(request.messages().get(currentIndex).role())) {
                throw invalidResponse();
            }
            Message current = request.messages().get(currentIndex);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", novaQuestion(current.content(), request.language()));
            body.put("response_language", request.language());
            body.put("session_id", request.sessionId());

            HttpRequest.Builder builder = HttpRequest.newBuilder(target.resolve("/chat"))
                    .timeout(Duration.ofMillis(bounded(properties.getReadTimeoutMs(), 1_000, 300_000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (request.turnId() != null) builder.header("X-Nova-Turn-Id", request.turnId());
            if (request.queueScope() != null) builder.header("X-Nova-Queue-Scope", request.queueScope());
            HttpRequest httpRequest = builder.build();
            HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new BizException(429, "NOVA_AI_BUSY");
            if (response.statusCode() == 409) throw new BizException(409, "NOVA_AI_TURN_CONFLICT");
            if (response.statusCode() == 504 || response.statusCode() == 408) {
                throw new BizException(504, "NOVA_AI_TIMEOUT");
            }
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) {
                throw unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("answer").asText("").trim();
            String model = root.path("model").asText("");
            if (!isAcceptedPublicRoute(model) || answer.isBlank()
                    || answer.length() > bounded(properties.getMaxOutputChars(), 256, 16_000)) {
                throw invalidResponse();
            }
            return answer;
        } catch (BizException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (HttpTimeoutException ex) {
            throw new BizException(504, "NOVA_AI_TIMEOUT");
        } catch (IOException | RuntimeException ex) {
            throw unavailable();
        }
    }

    @Override
    public boolean available() {
        if (properties.getMode() != NovaAiProperties.Mode.OLLAMA_LOCAL) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri().resolve("/health"))
                    .timeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 250, 10_000)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().length() > 128_000) return false;
            JsonNode root = objectMapper.readTree(response.body());
            return "ok".equals(root.path("status").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                // Uvicorn serves this loopback API over HTTP/1.1. Java's clear-text
                // HTTP/2 preference sends an h2c upgrade probe whose POST body is
                // discarded by uvicorn, producing a misleading 422 "body missing".
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(bounded(properties.getConnectTimeoutMs(), 100, 10_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI baseUri() {
        return localBaseUri(properties.getRagBaseUrl());
    }

    private URI localBaseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String rawHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                    ? rawHost.substring(1, rawHost.length() - 1)
                    : rawHost;
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
            throw new IllegalStateException("NOVA_AI_LOCAL_RAG_BASE_URL_INVALID");
        }
    }

    private BizException unavailable() {
        return new BizException(503, "NOVA_AI_UNAVAILABLE");
    }

    private BizException invalidResponse() {
        return new BizException(502, "NOVA_AI_RESPONSE_INVALID");
    }

    private String novaQuestion(String question, String language) {
        if (knowledgeRepository == null) return question;
        String normalizedLanguage = language == null ? "" : language.trim();
        String context = knowledgeRepository.listFaqs().stream()
                .filter(faq -> "PUBLISHED".equalsIgnoreCase(faq.status()))
                .filter(faq -> "Nova".equalsIgnoreCase(faq.surface()))
                .filter(faq -> normalizedLanguage.isBlank() || normalizedLanguage.equalsIgnoreCase(faq.language()))
                .sorted(java.util.Comparator.comparing(SupportFaqView::sortOrder).thenComparing(SupportFaqView::id))
                .limit(20)
                .map(faq -> "Q: " + boundedText(faq.question(), 500) + "\nA: " + boundedText(faq.answer(), 2_000))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (context.isBlank()) return question;
        return "Published Nova knowledge (use only when relevant):\n" + context + "\n\nUser question:\n" + question;
    }

    private String boundedText(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean isAcceptedPublicRoute(String value) {
        return "generated-answer".equals(value)
                || "retrieval-only".equals(value)
                || "retrieval-fallback".equals(value)
                || (value != null && value.startsWith("guardrail"));
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
