package ffdd.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.openapi.domain.OpenApiApp;
import ffdd.openapi.domain.OpenApiCallAudit;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.mapper.OpenApiAppMapper;
import ffdd.openapi.mapper.OpenApiCallAuditMapper;
import ffdd.openapi.mapper.WebhookDeliveryMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OpenApiOpsStatsService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String APP_ACTIVE = "ACTIVE";
    private static final String APP_DISABLED = "DISABLED";
    private static final String WEBHOOK_PENDING = "PENDING";
    private static final String WEBHOOK_SUCCESS = "SUCCESS";
    private static final String WEBHOOK_FAILED = "FAILED";
    private static final String WEBHOOK_DEAD = "DEAD";

    private final OpenApiAppMapper appMapper;
    private final OpenApiCallAuditMapper auditMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final Clock clock;

    @Autowired
    public OpenApiOpsStatsService(
            OpenApiAppMapper appMapper,
            OpenApiCallAuditMapper auditMapper,
            WebhookDeliveryMapper deliveryMapper) {
        this(appMapper, auditMapper, deliveryMapper, Clock.systemDefaultZone());
    }

    OpenApiOpsStatsService(
            OpenApiAppMapper appMapper,
            OpenApiCallAuditMapper auditMapper,
            WebhookDeliveryMapper deliveryMapper,
            Clock clock) {
        this.appMapper = appMapper;
        this.auditMapper = auditMapper;
        this.deliveryMapper = deliveryMapper;
        this.clock = clock;
    }

    public Map<String, Object> summary(int days) {
        int normalizedDays = normalizeDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(normalizedDays - 1L).toLocalDate().atStartOfDay();

        Map<String, Object> response = section(
                "service", "nexion-openapi-service",
                "days", normalizedDays,
                "startAt", since,
                "endAt", now);
        response.put("apps", section(
                "total", countApps(null),
                "active", countApps(APP_ACTIVE),
                "disabled", countApps(APP_DISABLED)));
        response.put("calls", section(
                "total", countCalls(since, null),
                "success", countCalls(since, 0),
                "failed", countFailedCalls(since)));
        response.put("webhooks", section(
                "total", countWebhooks(since, null),
                "pending", countWebhooks(since, WEBHOOK_PENDING),
                "success", countWebhooks(since, WEBHOOK_SUCCESS),
                "failed", countWebhooks(since, WEBHOOK_FAILED),
                "dead", countWebhooks(since, WEBHOOK_DEAD)));
        return response;
    }

    private long countApps(String status) {
        Long count = appMapper.selectCount(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getIsDeleted, 0)
                .eq(StringUtils.hasText(status), OpenApiApp::getStatus, status));
        return nullToZero(count);
    }

    private long countCalls(LocalDateTime since, Integer responseCode) {
        Long count = auditMapper.selectCount(new LambdaQueryWrapper<OpenApiCallAudit>()
                .eq(OpenApiCallAudit::getIsDeleted, 0)
                .ge(OpenApiCallAudit::getCreatedAt, since)
                .eq(responseCode != null, OpenApiCallAudit::getResponseCode, responseCode));
        return nullToZero(count);
    }

    private long countFailedCalls(LocalDateTime since) {
        Long count = auditMapper.selectCount(new LambdaQueryWrapper<OpenApiCallAudit>()
                .eq(OpenApiCallAudit::getIsDeleted, 0)
                .ge(OpenApiCallAudit::getCreatedAt, since)
                .ne(OpenApiCallAudit::getResponseCode, 0));
        return nullToZero(count);
    }

    private long countWebhooks(LocalDateTime since, String status) {
        Long count = deliveryMapper.selectCount(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getIsDeleted, 0)
                .ge(WebhookDelivery::getCreatedAt, since)
                .eq(StringUtils.hasText(status), WebhookDelivery::getStatus, status));
        return nullToZero(count);
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private long nullToZero(Long count) {
        return count == null ? 0 : count;
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
