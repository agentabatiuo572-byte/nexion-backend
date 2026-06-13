package ffdd.system.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.service.SystemConfigService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/public-config")
public class SystemPublicConfigController {
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    public SystemPublicConfigController(SystemConfigService systemConfigService, ObjectMapper objectMapper) {
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/growth")
    public ApiResult<Map<String, Object>> growth() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("growth")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("growth.")) {
                response.put(key.substring("growth.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    private Object typedValue(ConfigItemResponse item) {
        String valueType = item.getValueType() == null ? "STRING" : item.getValueType();
        String value = item.getConfigValue();
        return switch (valueType) {
            case "BOOLEAN" -> Boolean.parseBoolean(value);
            case "NUMBER" -> new BigDecimal(value);
            case "JSON" -> readJson(value);
            default -> value;
        };
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BizException("Public config JSON is invalid");
        }
    }
}
