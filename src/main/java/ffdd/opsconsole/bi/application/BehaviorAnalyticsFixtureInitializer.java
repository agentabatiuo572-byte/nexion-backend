package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Explicit, opt-in L6 acceptance fixture. Production defaults to zero writes. */
@Component
@Conditional(BehaviorAnalyticsFixtureProfileCondition.class)
@RequiredArgsConstructor
public class BehaviorAnalyticsFixtureInitializer implements ApplicationRunner {
    private static final String SESSION_ID = "6c360f7395f54f0b9334edc5ce841001";
    private static final String PAGE_EVENT_ID = "6c360f7395f54f0b9334edc5ce841002";
    private static final String CLICK_EVENT_ID = "6c360f7395f54f0b9334edc5ce841003";

    private final BehaviorAnalyticsService service;
    @Value("${nexion.analytics.fixture.enabled:false}")
    private final boolean enabled;
    @Value("${nexion.analytics.fixture.user-id:0}")
    private final long userId;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (userId <= 0) throw new BizException(503, "L6_FIXTURE_USER_ID_REQUIRED");
        long now = System.currentTimeMillis();
        service.ingestFixture(userId, new BehaviorEventRequest(
                PAGE_EVENT_ID, "app.page_viewed", SESSION_ID, "/pages/me/me",
                0L, null, null, null, null, now, "APP", "en-US"));
        service.ingestFixture(userId, new BehaviorEventRequest(
                CLICK_EVENT_ID, "app.element_clicked", SESSION_ID, "/pages/me/me",
                null, 0.5d, 0.5d, "MAIN_CTA", "fixture_cta", now + 400L, "APP", "en-US"));
    }
}
