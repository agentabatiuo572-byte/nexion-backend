package ffdd.openapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.openapi.domain.OpenApiApp;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OpenApiQuotaServiceTest {
    private final OpenApiQuotaCounter quotaCounter = mock(OpenApiQuotaCounter.class);
    private final OpenApiQuotaService quotaService = new OpenApiQuotaService(quotaCounter, true, 10, 1000);

    @Test
    void rejectsWhenQpsQuotaExceeded() {
        OpenApiApp app = app(2, 100);
        when(quotaCounter.incrementQps(app.getAppKey(), "/openapi/v1/compute/receipts")).thenReturn(3L);

        assertThatThrownBy(() -> quotaService.enforce(app, "/openapi/v1/compute/receipts"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("QPS quota exceeded");
    }

    @Test
    void rejectsWhenDailyQuotaExceeded() {
        OpenApiApp app = app(10, 1);
        String path = "/openapi/v1/compute/receipts";
        when(quotaCounter.incrementQps(app.getAppKey(), path)).thenReturn(1L);
        when(quotaCounter.incrementDaily(app.getAppKey(), path, LocalDate.now())).thenReturn(2L);

        assertThatThrownBy(() -> quotaService.enforce(app, path))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("daily quota exceeded");
    }

    @Test
    void usesDefaultQuotaWhenAppQuotaIsMissing() {
        OpenApiApp app = app(null, null);
        String path = "/openapi/v1/compute/receipts";
        when(quotaCounter.incrementQps(app.getAppKey(), path)).thenReturn(11L);

        assertThatThrownBy(() -> quotaService.enforce(app, path))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("QPS quota exceeded");
    }

    private OpenApiApp app(Integer qpsLimit, Integer dailyLimit) {
        OpenApiApp app = new OpenApiApp();
        app.setAppKey("nxak_test");
        app.setQpsLimit(qpsLimit);
        app.setDailyLimit(dailyLimit);
        return app;
    }
}
