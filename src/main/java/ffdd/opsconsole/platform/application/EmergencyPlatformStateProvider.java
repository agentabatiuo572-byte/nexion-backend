package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmergencyPlatformStateProvider implements PlatformEmergencyStateProvider {
    private final OpsKillSwitchService killSwitchService;
    private final OpsEmergencyControlService emergencyControlService;

    @Override
    public List<Map<String, Object>> currentKillSwitches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Map<String, Object> matrix = killSwitchService.matrix().getData();
            for (Object value : listValue(matrix.get("activeGates"))) {
                if (value instanceof Map<?, ?> gate) {
                    boolean enabled = Boolean.TRUE.equals(gate.get("enabled"));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", text(gate.get("key"), "unknown"));
                    row.put("name", text(gate.get("name"), "未知功能闸"));
                    row.put("status", enabled ? "enabled" : "disabled");
                    row.put("up", enabled);
                    row.put("lastChange", text(gate.get("lastChange"), "暂无变更时间"));
                    row.put("chain", "J1 应急控制 · nx_emergency_control_setting");
                    row.put("source", "J1:/api/admin/emergency/kill-switches");
                    rows.add(row);
                }
            }
        } catch (RuntimeException ex) {
            rows.add(unavailable("j1-unavailable", "J1 功能闸读取失败", "J1 应急控制"));
        }

        try {
            Map<String, Object> overview = emergencyControlService.geoBlockOverview().getData();
            int blocked = listValue(overview.get("blocked")).size();
            int limited = listValue(overview.get("limited")).size();
            boolean clear = blocked == 0 && limited == 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", "geo-block");
            row.put("name", "地区屏蔽");
            row.put("status", clear ? "空列表 · 无封锁" : blocked + " 个屏蔽 · " + limited + " 个限流");
            row.put("up", clear);
            row.put("lastChange", latestChange(overview.get("recentChanges")));
            row.put("chain", "J2 地区屏蔽 · nx_emergency_geo_country_policy");
            row.put("source", "J2:/api/admin/emergency/geo-block");
            rows.add(row);
        } catch (RuntimeException ex) {
            rows.add(unavailable("j2-unavailable", "J2 地区屏蔽读取失败", "J2 地区屏蔽"));
        }
        return List.copyOf(rows);
    }

    private Map<String, Object> unavailable(String key, String name, String chain) {
        return Map.of(
                "key", key,
                "name", name,
                "status", "读取失败",
                "up", false,
                "lastChange", LocalDateTime.now().toString(),
                "chain", chain + " · 当前不可确认",
                "source", chain);
    }

    private String latestChange(Object value) {
        List<?> changes = listValue(value);
        if (changes.isEmpty()) {
            return "暂无变更记录";
        }
        Object first = changes.get(0);
        if (first instanceof Map<?, ?> row) {
            for (String key : List.of("createdAt", "updatedAt", "changedAt", "time")) {
                if (row.get(key) != null) {
                    return String.valueOf(row.get(key));
                }
            }
        }
        return String.valueOf(first);
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
