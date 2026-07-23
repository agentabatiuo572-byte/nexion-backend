package ffdd.opsconsole.platform.domain;

import java.util.List;

/** Read-only source for every active server-owned platform configuration item. */
public interface PlatformParamRegistrySource {
    List<PlatformConfigItem> findAllActive();
}
