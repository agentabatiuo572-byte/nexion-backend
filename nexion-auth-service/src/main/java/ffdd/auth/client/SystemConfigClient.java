package ffdd.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import ffdd.common.security.AuthHeaders;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SystemConfigClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SystemConfigClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${nexion.services.system-url:http://localhost:8110}") String systemUrl,
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        this.objectMapper = objectMapper;
        this.restClient = builder
                .baseUrl(systemUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(AuthHeaders.GATEWAY_SECRET, gatewaySecret)
                .defaultHeader(AuthHeaders.SUBJECT_ID, "0")
                .defaultHeader(AuthHeaders.SUBJECT_TYPE, "SERVICE")
                .defaultHeader(AuthHeaders.USERNAME, "nexion-auth-service")
                .defaultHeader(AuthHeaders.AUTHORITIES, "PERM_SYSTEM_READ,PERM_SYSTEM_WRITE")
                .build();
    }

    public ConfigItem upsert(ConfigItem payload) {
        Optional<ConfigItem> existing = findByKey(payload.configKey());
        if (existing.isPresent()) {
            return patch(existing.get().id(), new ConfigItemUpdate(
                    payload.configValue(),
                    payload.valueType(),
                    payload.configGroup(),
                    payload.visibility(),
                    payload.remark(),
                    payload.status()));
        }
        return post(payload);
    }

    private Optional<ConfigItem> findByKey(String configKey) {
        JsonNode data = data(restClient.get()
                .uri(uri -> uri.path("/system/configs")
                        .queryParam("query", configKey)
                        .queryParam("limit", 20)
                        .build())
                .retrieve()
                .body(JsonNode.class));
        if (data == null || !data.isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : data) {
            if (configKey.equals(item.path("configKey").asText())) {
                return Optional.of(objectMapper.convertValue(item, ConfigItem.class));
            }
        }
        return Optional.empty();
    }

    private ConfigItem post(ConfigItem payload) {
        JsonNode data = data(restClient.post()
                .uri("/system/configs")
                .body(payload)
                .retrieve()
                .body(JsonNode.class));
        return objectMapper.convertValue(data, ConfigItem.class);
    }

    private ConfigItem patch(Long id, ConfigItemUpdate payload) {
        JsonNode data = data(restClient.patch()
                .uri("/system/configs/{id}", id)
                .body(payload)
                .retrieve()
                .body(JsonNode.class));
        return objectMapper.convertValue(data, ConfigItem.class);
    }

    private JsonNode data(JsonNode response) {
        if (response == null) {
            throw new BizException("System config response is empty");
        }
        if (response.path("code").asInt(-1) != 0) {
            throw new BizException(response.path("message").asText("System config request failed"));
        }
        return response.get("data");
    }

    public record ConfigItem(
            Long id,
            String configKey,
            String configValue,
            String valueType,
            String configGroup,
            String visibility,
            String remark,
            Integer status) {
    }

    public record ConfigItemUpdate(
            String configValue,
            String valueType,
            String configGroup,
            String visibility,
            String remark,
            Integer status) {
    }
}
