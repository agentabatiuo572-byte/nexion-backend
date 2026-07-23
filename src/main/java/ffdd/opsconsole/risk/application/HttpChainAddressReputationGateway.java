package ffdd.opsconsole.risk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail-closed HTTP adapter. The provider contract is POST {chain,address} -> {score: 0..1}. */
@Component
@RequiredArgsConstructor
public class HttpChainAddressReputationGateway implements ChainAddressReputationGateway {
    private final K3AddressReputationProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public BigDecimal score(String chain, String address) {
        URI endpoint = validatedEndpoint();
        if (!StringUtils.hasText(chain) || !StringUtils.hasText(address)) {
            throw unavailable();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                    .build();
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "chain", chain.trim(),
                    "address", address.trim()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header(properties.getApiKeyHeader().trim(), properties.getApiKey().trim())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode scoreNode = root == null ? null : root.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw invalid();
            }
            BigDecimal score = scoreNode.decimalValue();
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                throw invalid();
            }
            return score;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private URI validatedEndpoint() {
        if (!StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getApiKeyHeader())
                || properties.getConnectTimeoutMs() <= 0
                || properties.getReadTimeoutMs() <= 0) {
            throw unavailable();
        }
        try {
            URI endpoint = URI.create(properties.getEndpoint().trim());
            if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || !StringUtils.hasText(endpoint.getHost())) {
                throw unavailable();
            }
            return endpoint;
        } catch (IllegalArgumentException invalidEndpoint) {
            throw unavailable();
        }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("K3_ADDRESS_REPUTATION_UNAVAILABLE");
    }

    private IllegalStateException invalid() {
        return new IllegalStateException("K3_ADDRESS_REPUTATION_INVALID");
    }
}
