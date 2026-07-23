package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.mapper.K4WithdrawalAlertMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class K4WithdrawalAlertService {
    private final K4WithdrawalAlertMapper mapper;

    public ApiResult<Map<String, Object>> alerts(Long adminId) {
        List<Map<String, Object>> alerts = mapper.listAlerts(requireAdminId(adminId), 50).stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.eventId());
                    item.put("domain", "K4");
                    item.put("level", "critical");
                    item.put("title", "K4 提现风险升级");
                    item.put("hint", row.withdrawalNo() + " · 风险分 " + row.riskScore()
                            + " · " + row.priority());
                    item.put("withdrawalNo", row.withdrawalNo());
                    item.put("riskScore", row.riskScore());
                    item.put("priority", row.priority());
                    item.put("modelVersion", row.modelVersion());
                    item.put("scoreAsOf", row.scoreAsOf());
                    item.put("read", row.readAt() != null);
                    item.put("createdAt", row.createdAt());
                    return item;
                }).toList();
        return ApiResult.ok(Map.of("alerts", alerts, "source", "nx_k4_withdrawal_alert_receipt"));
    }

    public ApiResult<Map<String, Object>> markRead(Long adminId, String eventId) {
        if (!StringUtils.hasText(eventId)) return ApiResult.fail(400, "K4_ALERT_EVENT_ID_REQUIRED");
        if (mapper.markRead(eventId.trim(), requireAdminId(adminId)) != 1) {
            return ApiResult.fail(404, "K4_WITHDRAWAL_ALERT_NOT_FOUND");
        }
        return ApiResult.ok(Map.of("eventId", eventId.trim(), "read", true));
    }

    private Long requireAdminId(Long adminId) {
        if (adminId == null || adminId <= 0) throw new IllegalArgumentException("ADMIN_ID_REQUIRED");
        return adminId;
    }
}
