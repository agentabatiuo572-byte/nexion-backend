package ffdd.opsconsole.platform.application;


import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlatformConfigFacadeAdapter implements PlatformConfigFacade {
    private final PlatformConfigRepository configRepository;
    private final SessionLifetimeCache sessionLifetimeCache;

    public PlatformConfigFacadeAdapter(PlatformConfigRepository configRepository) {
        this(configRepository, null);
    }

    @Autowired
    PlatformConfigFacadeAdapter(PlatformConfigRepository configRepository, SessionLifetimeCache sessionLifetimeCache) {
        this.configRepository = configRepository;
        this.sessionLifetimeCache = sessionLifetimeCache;
    }

    @Override
    public Optional<String> activeValue(String configKey) {
        return configRepository.findActiveByKey(configKey).map(PlatformConfigItem::configValue);
    }

    @Override
    public Optional<String> storedValue(String configKey) {
        return configRepository.findStoredByKey(configKey).map(PlatformConfigItem::configValue);
    }

    @Override
    public SessionLifetimes sessionLifetimes() {
        return sessionLifetimeCache == null ? PlatformConfigFacade.super.sessionLifetimes() : sessionLifetimeCache.read();
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
        if (sessionLifetimeCache != null) sessionLifetimeCache.invalidateAfterCommit(configKey);
    }

    @Override
    public boolean insertAdminValueIfMissing(
            String configKey, String configValue, String valueType, String configGroup, String remark) {
        boolean inserted = configRepository.insertIfAbsent(newAdminValue(configKey, configValue, valueType, configGroup, remark));
        if (inserted && sessionLifetimeCache != null) sessionLifetimeCache.invalidateAfterCommit(configKey);
        return inserted;
    }

    @Override
    public boolean upsertAdminValueIfUnconfigured(
            String configKey, String configValue, String valueType, String configGroup, String remark) {
        PlatformConfigItem value = newAdminValue(configKey, configValue, valueType, configGroup, remark);
        boolean changed = configRepository.reactivateIfInactive(value) || configRepository.insertIfAbsent(value);
        if (changed && sessionLifetimeCache != null) sessionLifetimeCache.invalidateAfterCommit(configKey);
        return changed;
    }

    private PlatformConfigItem newAdminValue(
            String configKey, String configValue, String valueType, String configGroup, String remark) {
        LocalDateTime now = LocalDateTime.now();
        return new PlatformConfigItem(
                null,
                configKey,
                configValue,
                valueType,
                configGroup,
                "ADMIN",
                remark,
                1,
                now,
                now);
    }

    @Override
    public Map<String, String> activeValuesByGroup(String configGroup) {
        return configRepository.findActiveByGroups(java.util.List.of(configGroup)).stream()
                .collect(Collectors.toMap(PlatformConfigItem::configKey, PlatformConfigItem::configValue, (left, right) -> right));
    }
}
