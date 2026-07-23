package ffdd.opsconsole.platform.application;

import java.util.List;
import java.util.Map;

public interface PlatformEmergencyStateProvider {
    List<Map<String, Object>> currentKillSwitches();
}
