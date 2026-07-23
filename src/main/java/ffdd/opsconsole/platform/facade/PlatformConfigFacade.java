package ffdd.opsconsole.platform.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Map;
import java.util.Optional;

public interface PlatformConfigFacade extends DomainFacade {
    Optional<String> activeValue(String configKey);

    /** Reads the current value under a database row lock when called inside a transaction. */
    default Optional<String> activeValueForUpdate(String configKey) {
        return activeValue(configKey);
    }

    void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark);

    default Map<String, String> activeValuesByGroup(String configGroup) {
        return Map.of();
    }
}
