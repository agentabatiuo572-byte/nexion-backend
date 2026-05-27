package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.ConfigItem;
import ffdd.system.dto.ConfigItemCreateRequest;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.dto.ConfigItemUpdateRequest;
import ffdd.system.mapper.ConfigItemMapper;
import ffdd.system.service.SystemConfigService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {
    private static final int ACTIVE = 1;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_CONFIG_VALUE_LENGTH = 65_535;
    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern CONFIG_GROUP_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Set<String> VALUE_TYPES = Set.of("STRING", "NUMBER", "BOOLEAN", "JSON");
    private static final Set<String> VISIBILITIES = Set.of("ADMIN", "PUBLIC");

    private final ConfigItemMapper configItemMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<ConfigItemResponse> list(String query, Integer status, int limit) {
        String normalizedQuery = trimToNull(query);
        LambdaQueryWrapper<ConfigItem> wrapper = new LambdaQueryWrapper<ConfigItem>()
                .eq(ConfigItem::getIsDeleted, 0)
                .eq(status != null, ConfigItem::getStatus, status)
                .and(StringUtils.hasText(normalizedQuery), nested -> nested
                        .like(ConfigItem::getConfigKey, normalizedQuery)
                        .or()
                        .like(ConfigItem::getRemark, normalizedQuery))
                .orderByDesc(ConfigItem::getUpdatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return configItemMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ConfigItemResponse getActiveByKey(String configKey) {
        String normalizedKey = normalizeConfigKey(configKey);
        ConfigItem item = configItemMapper.selectOne(new LambdaQueryWrapper<ConfigItem>()
                .eq(ConfigItem::getConfigKey, normalizedKey)
                .eq(ConfigItem::getStatus, ACTIVE)
                .eq(ConfigItem::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (item == null) {
            throw new BizException(404, "Config item not found");
        }
        return toResponse(item);
    }

    @Override
    public List<ConfigItemResponse> batchGetActive(List<String> configKeys) {
        List<String> normalizedKeys = normalizeBatchKeys(configKeys);
        List<ConfigItem> items = configItemMapper.selectList(new LambdaQueryWrapper<ConfigItem>()
                .in(ConfigItem::getConfigKey, normalizedKeys)
                .eq(ConfigItem::getStatus, ACTIVE)
                .eq(ConfigItem::getIsDeleted, 0));
        Map<String, ConfigItem> byKey = items.stream()
                .collect(Collectors.toMap(ConfigItem::getConfigKey, item -> item, (left, right) -> left));
        return normalizedKeys.stream()
                .map(byKey::get)
                .filter(item -> item != null)
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<ConfigItemResponse> listPublicByGroup(String configGroup) {
        String normalizedGroup = normalizeConfigGroup(configGroup);
        return configItemMapper.selectList(new LambdaQueryWrapper<ConfigItem>()
                        .eq(ConfigItem::getConfigGroup, normalizedGroup)
                        .eq(ConfigItem::getVisibility, "PUBLIC")
                        .eq(ConfigItem::getStatus, ACTIVE)
                        .eq(ConfigItem::getIsDeleted, 0)
                        .orderByAsc(ConfigItem::getConfigKey))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ConfigItemResponse create(ConfigItemCreateRequest request) {
        if (request == null) {
            throw new BizException("Config item request is required");
        }
        String configKey = normalizeConfigKey(request.getConfigKey());
        ConfigItem existing = configItemMapper.selectOne(new LambdaQueryWrapper<ConfigItem>()
                .eq(ConfigItem::getConfigKey, configKey)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BizException(409, "Config item already exists");
        }

        ConfigItem item = new ConfigItem();
        item.setConfigKey(configKey);
        String valueType = normalizeValueType(request.getValueType());
        item.setConfigValue(requireConfigValue(request.getConfigValue(), valueType));
        item.setValueType(valueType);
        item.setConfigGroup(normalizeConfigGroupOrDefault(request.getConfigGroup(), configKey));
        item.setVisibility(normalizeVisibility(request.getVisibility()));
        item.setRemark(trimToNull(request.getRemark()));
        item.setStatus(request.getStatus() == null ? ACTIVE : request.getStatus());
        item.setIsDeleted(0);
        configItemMapper.insert(item);
        return toResponse(item);
    }

    @Override
    public ConfigItemResponse update(Long id, ConfigItemUpdateRequest request) {
        if (id == null) {
            throw new BizException("Config item id is required");
        }
        if (request == null) {
            throw new BizException("Config item request is required");
        }
        ConfigItem item = configItemMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.getIsDeleted())) {
            throw new BizException(404, "Config item not found");
        }
        String valueType = item.getValueType();
        if (request.getValueType() != null) {
            valueType = normalizeValueType(request.getValueType());
            item.setValueType(valueType);
        }
        if (request.getConfigValue() != null) {
            item.setConfigValue(requireConfigValue(request.getConfigValue(), valueType));
        } else if (request.getValueType() != null) {
            requireConfigValue(item.getConfigValue(), valueType);
        }
        if (request.getConfigGroup() != null) {
            item.setConfigGroup(normalizeConfigGroup(request.getConfigGroup()));
        }
        if (request.getVisibility() != null) {
            item.setVisibility(normalizeVisibility(request.getVisibility()));
        }
        if (request.getRemark() != null) {
            item.setRemark(trimToNull(request.getRemark()));
        }
        if (request.getStatus() != null) {
            item.setStatus(request.getStatus());
        }
        configItemMapper.updateById(item);
        return toResponse(item);
    }

    private ConfigItemResponse toResponse(ConfigItem item) {
        return new ConfigItemResponse(
                item.getId(),
                item.getConfigKey(),
                item.getConfigValue(),
                item.getValueType(),
                item.getConfigGroup(),
                item.getVisibility(),
                item.getRemark(),
                item.getStatus(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    private List<String> normalizeBatchKeys(List<String> configKeys) {
        if (configKeys == null || configKeys.isEmpty()) {
            throw new BizException("configKeys is required");
        }
        if (configKeys.size() > MAX_BATCH_SIZE) {
            throw new BizException("configKeys size must be <= " + MAX_BATCH_SIZE);
        }
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (String configKey : configKeys) {
            ordered.put(normalizeConfigKey(configKey), Boolean.TRUE);
        }
        return List.copyOf(ordered.keySet());
    }

    private String normalizeConfigKey(String configKey) {
        String normalized = trimToNull(configKey);
        if (!StringUtils.hasText(normalized) || !CONFIG_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Invalid configKey");
        }
        return normalized;
    }

    private String normalizeConfigGroupOrDefault(String configGroup, String configKey) {
        String normalized = trimToNull(configGroup);
        if (StringUtils.hasText(normalized)) {
            return normalizeConfigGroup(normalized);
        }
        int dot = configKey.indexOf('.');
        return dot > 0 ? configKey.substring(0, dot) : "general";
    }

    private String normalizeConfigGroup(String configGroup) {
        String normalized = trimToNull(configGroup);
        if (!StringUtils.hasText(normalized) || !CONFIG_GROUP_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Invalid configGroup");
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        String normalized = trimToNull(visibility);
        if (!StringUtils.hasText(normalized)) {
            return "ADMIN";
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(normalized)) {
            throw new BizException("Invalid visibility");
        }
        return normalized;
    }

    private String requireConfigValue(String value, String valueType) {
        if (value == null) {
            throw new BizException("configValue is required");
        }
        if (value.length() > MAX_CONFIG_VALUE_LENGTH) {
            throw new BizException("configValue length must be <= " + MAX_CONFIG_VALUE_LENGTH);
        }
        if ("JSON".equals(valueType)) {
            validateJson(value);
        }
        return value;
    }

    private void validateJson(String value) {
        try {
            objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new BizException("configValue must be valid JSON");
        }
    }

    private String normalizeValueType(String valueType) {
        String normalized = trimToNull(valueType);
        if (!StringUtils.hasText(normalized)) {
            return "STRING";
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!VALUE_TYPES.contains(normalized)) {
            throw new BizException("Invalid valueType");
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
