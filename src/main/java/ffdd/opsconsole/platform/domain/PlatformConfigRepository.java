package ffdd.opsconsole.platform.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformConfigRepository {
    Optional<PlatformConfigItem> findActiveByKey(String configKey);

    default List<PlatformConfigItem> findActiveByKeys(Collection<String> configKeys) {
        return configKeys.stream().map(this::findActiveByKey).flatMap(Optional::stream).toList();
    }

    default Optional<PlatformConfigItem> findActiveByKeyForUpdate(String configKey) {
        return findActiveByKey(configKey);
    }

    default Optional<PlatformConfigItem> findAnyByKey(String configKey) {
        return findActiveByKey(configKey);
    }

    List<PlatformConfigItem> findActiveByGroups(Collection<String> configGroups);

    PlatformConfigItem save(PlatformConfigItem item);

    /** Atomic insert guarded by the unique config_key index; never revives or updates an existing row. */
    default boolean insertIfAbsent(PlatformConfigItem item) {
        return false;
    }
}
