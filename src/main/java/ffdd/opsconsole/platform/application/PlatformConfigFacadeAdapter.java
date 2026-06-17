package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PlatformConfigFacadeAdapter implements PlatformConfigFacade {
    private final PlatformConfigRepository configRepository;

    public PlatformConfigFacadeAdapter(PlatformConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    public Optional<String> activeValue(String configKey) {
        return configRepository.findActiveByKey(configKey).map(PlatformConfigItem::configValue);
    }

    @Override
    public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
        PlatformConfigItem existing = configRepository.findActiveByKey(configKey).orElseGet(() -> new PlatformConfigItem(
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
}
