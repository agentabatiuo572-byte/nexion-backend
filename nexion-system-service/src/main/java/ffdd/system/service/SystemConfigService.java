package ffdd.system.service;

import ffdd.system.dto.ConfigItemCreateRequest;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.dto.ConfigItemUpdateRequest;
import java.util.List;

public interface SystemConfigService {
    List<ConfigItemResponse> list(String query, Integer status, int limit);

    ConfigItemResponse getActiveByKey(String configKey);

    List<ConfigItemResponse> batchGetActive(List<String> configKeys);

    List<ConfigItemResponse> listPublicByGroup(String configGroup);

    ConfigItemResponse create(ConfigItemCreateRequest request);

    ConfigItemResponse update(Long id, ConfigItemUpdateRequest request);
}
