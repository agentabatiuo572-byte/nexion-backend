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
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
public class PublicConfigController {
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    public PublicConfigController(SystemConfigService systemConfigService, ObjectMapper objectMapper) {
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/day-one")
    public ApiResult<Map<String, Object>> dayOne() {
        ConfigItemResponse config = publicItem("onboarding", "onboarding.day0");
        Object value = typedValue(config);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> response = new LinkedHashMap<>();
            map.forEach((key, itemValue) -> response.put(String.valueOf(key), itemValue));
            return ApiResult.ok(response);
        }
        return ApiResult.ok(Map.of("value", value));
    }

    @GetMapping("/features")
    public ApiResult<Map<String, Object>> features() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("feature")) {
            String key = item.getConfigKey();
            response.put(key.startsWith("feature.") ? key.substring("feature.".length()) : key, typedValue(item));
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/device-fleet")
    public ApiResult<Map<String, Object>> deviceFleet() {
        Object value = typedValue(publicItem("compute", "compute.active_device_slots.default"));
        return ApiResult.ok(Map.of("maxActiveSlots", positiveInt(value, "max active slots")));
    }

    private ConfigItemResponse publicItem(String group, String key) {
        List<ConfigItemResponse> items = systemConfigService.listPublicByGroup(group);
        return items.stream()
                .filter(item -> key.equals(item.getConfigKey()))
                .findFirst()
                .orElseThrow(() -> new BizException(404, "Public config item not found"));
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

    private int positiveInt(Object value, String name) {
        try {
            int result = value instanceof BigDecimal decimal
                    ? decimal.intValueExact()
                    : Integer.parseInt(String.valueOf(value));
            if (result < 1) {
                throw new BizException("Public config " + name + " is invalid");
            }
            return result;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new BizException("Public config " + name + " is invalid");
        }
    }
}
