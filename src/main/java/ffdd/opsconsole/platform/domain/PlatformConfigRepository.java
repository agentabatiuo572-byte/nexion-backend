package ffdd.opsconsole.platform.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformConfigRepository {
    Optional<PlatformConfigItem> findActiveByKey(String configKey);

    List<PlatformConfigItem> findActiveByGroups(Collection<String> configGroups);

    PlatformConfigItem save(PlatformConfigItem item);
}
