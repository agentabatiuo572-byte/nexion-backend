package ffdd.opsconsole.platform.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Optional;

public interface PlatformConfigFacade extends DomainFacade {
    Optional<String> activeValue(String configKey);

    void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark);
}
