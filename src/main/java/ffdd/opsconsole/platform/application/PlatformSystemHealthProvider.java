package ffdd.opsconsole.platform.application;

import java.util.List;
import java.util.Map;

public interface PlatformSystemHealthProvider {
    List<Map<String, Object>> currentHealth();
}
