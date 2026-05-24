package ffdd.openapi.service;

import ffdd.common.exception.BizException;
import ffdd.openapi.domain.OpenApiApp;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OpenApiQuotaService {
    private final OpenApiQuotaCounter quotaCounter;
    private final boolean enabled;
    private final int defaultQpsLimit;
    private final int defaultDailyLimit;

    public OpenApiQuotaService(
            OpenApiQuotaCounter quotaCounter,
            @Value("${nexion.openapi.quota.enabled:true}") boolean enabled,
            @Value("${nexion.openapi.quota.default-qps-limit:20}") int defaultQpsLimit,
            @Value("${nexion.openapi.quota.default-daily-limit:10000}") int defaultDailyLimit) {
        this.quotaCounter = quotaCounter;
        this.enabled = enabled;
        this.defaultQpsLimit = Math.max(1, defaultQpsLimit);
        this.defaultDailyLimit = Math.max(1, defaultDailyLimit);
    }

    public void enforce(OpenApiApp app, String apiPath) {
        if (!enabled) {
            return;
        }
        int qpsLimit = positiveOrDefault(app.getQpsLimit(), defaultQpsLimit);
        long qpsCount = quotaCounter.incrementQps(app.getAppKey(), apiPath);
        if (qpsCount > qpsLimit) {
            throw new BizException(429, "OpenAPI QPS quota exceeded");
        }

        int dailyLimit = positiveOrDefault(app.getDailyLimit(), defaultDailyLimit);
        long dailyCount = quotaCounter.incrementDaily(app.getAppKey(), apiPath, LocalDate.now());
        if (dailyCount > dailyLimit) {
            throw new BizException(429, "OpenAPI daily quota exceeded");
        }
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value < 1 ? defaultValue : value;
    }
}
