package ffdd.opsconsole.platform.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformConfigFacadeAdapter implements PlatformConfigFacade {
    private final PlatformConfigRepository configRepository;

    @Override
    public Optional<String> activeValue(String configKey) {
        return configRepository.findActiveByKey(configKey).map(PlatformConfigItem::configValue);
    }

    @Override
    public Optional<String> activeValueForUpdate(String configKey) {
        return configRepository.findActiveByKeyForUpdate(configKey).map(PlatformConfigItem::configValue);
    }

    @Override
    public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
        PlatformConfigItem existing = configRepository.findActiveByKey(configKey)
                .or(() -> configRepository.findAnyByKey(configKey))
                .orElseGet(() -> new PlatformConfigItem(
                null,
                configKey,
                configValue,
                valueType,
                configGroup,
                "ADMIN",
                remark,
                1,
                LocalDateTime.now(),
                LocalDateTime.now()));
        PlatformConfigItem saved = new PlatformConfigItem(
                existing.id(),
                existing.configKey(),
                configValue,
                valueType,
                configGroup,
                "ADMIN",
                remark,
                1,
                existing.createdAt(),
                LocalDateTime.now());
        configRepository.save(saved);
    }

    @Override
    public Map<String, String> activeValuesByGroup(String configGroup) {
        return configRepository.findActiveByGroups(java.util.List.of(configGroup)).stream()
                .collect(Collectors.toMap(PlatformConfigItem::configKey, PlatformConfigItem::configValue, (left, right) -> right));
    }
}
