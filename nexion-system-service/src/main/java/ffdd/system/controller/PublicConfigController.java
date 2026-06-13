package ffdd.system.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.dto.ContentPageResponse;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.service.SystemConfigService;
import ffdd.system.service.SystemContentService;
import ffdd.system.service.SystemHelpService;
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
    private static final String DAY_ONE_PREFIX = "onboarding.day0.";

    private final SystemConfigService systemConfigService;
    private final SystemContentService systemContentService;
    private final SystemHelpService systemHelpService;
    private final ObjectMapper objectMapper;

    public PublicConfigController(
            SystemConfigService systemConfigService,
            SystemContentService systemContentService,
            SystemHelpService systemHelpService,
            ObjectMapper objectMapper) {
        this.systemConfigService = systemConfigService;
        this.systemContentService = systemContentService;
        this.systemHelpService = systemHelpService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/day-one")
    public ApiResult<Map<String, Object>> dayOne() {
        List<ConfigItemResponse> items = systemConfigService.listPublicByGroup("onboarding");
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : items) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith(DAY_ONE_PREFIX)) {
                response.put(toDayOneResponseKey(key.substring(DAY_ONE_PREFIX.length())), typedValue(item));
            }
        }
        if (!response.isEmpty()) {
            return ApiResult.ok(response);
        }

        ConfigItemResponse config = findPublicItem(items, "onboarding.day0");
        if (config == null) {
            throw new BizException(404, "Public config item not found");
        }
        Object value = typedValue(config);
        if (value instanceof Map<?, ?> map) {
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

    @GetMapping("/wallet")
    public ApiResult<Map<String, Object>> wallet() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("wallet")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("wallet.")) {
                response.put(key.substring("wallet.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/team")
    public ApiResult<Map<String, Object>> team() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("team")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("team.")) {
                response.put(key.substring("team.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/commerce")
    public ApiResult<Map<String, Object>> commerce() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("commerce")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("commerce.")) {
                response.put(key.substring("commerce.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
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

    @GetMapping("/openapi")
    public ApiResult<Map<String, Object>> openapi() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("openapi")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("openapi.")) {
                response.put(key.substring("openapi.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/compliance")
    public ApiResult<Map<String, Object>> compliance() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("compliance")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("compliance.")) {
                response.put(key.substring("compliance.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/profile")
    public ApiResult<Map<String, Object>> profile() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (ConfigItemResponse item : systemConfigService.listPublicByGroup("profile")) {
            String key = item.getConfigKey();
            if (key != null && key.startsWith("profile.")) {
                response.put(key.substring("profile.".length()), typedValue(item));
            }
        }
        return ApiResult.ok(response);
    }

    @GetMapping("/platform-phase")
    public ApiResult<Map<String, Object>> platformPhase() {
        return generationGates();
    }

    @GetMapping("/generation-gates")
    public ApiResult<Map<String, Object>> generationGates() {
        Object value = typedValue(publicItem("platform", "platform.phase.config"));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> response = new LinkedHashMap<>();
            map.forEach((key, itemValue) -> response.put(String.valueOf(key), itemValue));
            return ApiResult.ok(response);
        }
        return ApiResult.ok(Map.of("value", value));
    }

    @GetMapping("/content/pages")
    public ApiResult<List<ContentPageResponse>> publicContentPages(
            String query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemContentService.list(query, 1, limit));
    }

    @GetMapping("/content/pages/{pageCode}")
    public ApiResult<ContentPageResponse> publicContentPage(
            @org.springframework.web.bind.annotation.PathVariable String pageCode) {
        return ApiResult.ok(systemContentService.getActiveByCode(pageCode));
    }

    @GetMapping("/help/articles")
    public ApiResult<List<HelpArticleResponse>> publicHelpArticles(
            String query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemHelpService.list(query, 1, limit));
    }

    @GetMapping("/help/articles/page")
    public ApiResult<PageResult<HelpArticleResponse>> publicHelpArticlesPage(
            String query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") long pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(systemHelpService.page(query, 1, pageNum, pageSize));
    }

    @GetMapping("/help/articles/{articleCode}")
    public ApiResult<HelpArticleResponse> publicHelpArticle(
            @org.springframework.web.bind.annotation.PathVariable String articleCode) {
        return ApiResult.ok(systemHelpService.getActiveByCode(articleCode));
    }

    private ConfigItemResponse publicItem(String group, String key) {
        List<ConfigItemResponse> items = systemConfigService.listPublicByGroup(group);
        ConfigItemResponse item = findPublicItem(items, key);
        if (item == null) {
            throw new BizException(404, "Public config item not found");
        }
        return item;
    }

    private ConfigItemResponse findPublicItem(List<ConfigItemResponse> items, String key) {
        return items.stream()
                .filter(item -> key.equals(item.getConfigKey()))
                .findFirst()
                .orElse(null);
    }

    private String toDayOneResponseKey(String suffix) {
        return switch (suffix) {
            case "first_receipt_target_seconds" -> "firstReceiptTargetSeconds";
            case "first_receipt_usdt" -> "firstReceiptUsdt";
            case "welcome_bonus_asset" -> "welcomeBonusAsset";
            case "welcome_bonus_amount" -> "welcomeBonusAmount";
            case "active_device_count" -> "activeDeviceCount";
            case "paid_today_usdt" -> "paidTodayUsdt";
            case "intro_refresh_seconds" -> "introRefreshSeconds";
            default -> snakeToLowerCamel(suffix);
        };
    }

    private String snakeToLowerCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '_') {
                upperNext = true;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        return result.toString();
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
