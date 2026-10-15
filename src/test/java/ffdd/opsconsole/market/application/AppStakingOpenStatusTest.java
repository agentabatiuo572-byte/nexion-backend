package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.market.mapper.MarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppStakingOpenStatusTest {
    private static final Long USER = 60723153007L;
    private static final String SCOPE = "APP:G1_STAKING_OPEN:USER:" + USER;
    private static final String HASH = "ad5bc99b62ed3964ea77c7e780185c9532fa25a864586c6e91f91af8f9c4353a";
    private final AdminIdempotencyRecordMapper records = mock(AdminIdempotencyRecordMapper.class);
    private final AdminIdempotencyExpiryTransitionExecutor expiry = mock(AdminIdempotencyExpiryTransitionExecutor.class);
    private final AppStakingMapper mapper = mock(AppStakingMapper.class);
    private final MarketSandboxMapper sandbox = mock(MarketSandboxMapper.class);
    private final SupportStakingFundAdapter fundAdapter = mock(SupportStakingFundAdapter.class);
    private final RiskDisclosureGateFacade disclosure = mock(RiskDisclosureGateFacade.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("unused", "true");
    private final Clock clock = Clock.systemUTC();
    private final AdminIdempotencyService idempotency = new AdminIdempotencyService(
            new AdminIdempotencyTransactionExecutor(records, new ObjectMapper(), expiry), clock);
    private final AppStakingService service = new AppStakingService(
            mapper, disclosure, config, idempotency, outbox, audit, earnings, clock, environment, sandbox, fundAdapter);

    @Test
    void readsEveryOwnedStateAndExpiredRetainedReceiptUsingOnlySelectCurrent() {
        environment.setActiveProfiles("dev");
        for (String status : List.of("FAILED", "SUCCEEDED", "PROCESSING", "UNKNOWN")) {
            var row = new AdminIdempotencyRecordEntity();
            row.setIsDeleted(0); row.setStatus(status); row.setRequestHash(HASH);
            row.setExpiresAt(LocalDateTime.of(2020, 1, 1, 0, 0));
            row.setErrorMessage("private sql must stay private");
            row.setResponseJson("private historical wallet must not be deserialized");
            when(records.selectCurrent(SCOPE, "test-only-original-key")).thenReturn(row);
            var result = service.openStatus(USER, "test-only-original-key", "usdt30d", "20.0000001").getData();
            assertThat(result).containsEntry("status", status).containsEntry("sourceEnvironment", "PRODUCTION")
                    .containsEntry("runId", "").containsEntry("stakingCommandProtocol", 1).hasSize(4);
        }
        verify(records, times(4)).selectCurrent(SCOPE, "test-only-original-key");
        verifyNoMoreInteractions(records);
        verifyNoInteractions(mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void wrongAmountOtherUserMissingDeletedAndUnknownReceiptsCannotResolveAnIntent() {
        environment.setActiveProfiles("prod");
        var row = new AdminIdempotencyRecordEntity();
        row.setIsDeleted(0); row.setStatus("SUCCEEDED"); row.setRequestHash(HASH); row.setResponseJson("{}");
        when(records.selectCurrent(SCOPE, "test-only-original-key")).thenReturn(row);
        assertThat(service.openStatus(USER, "test-only-original-key", "usdt30d", "21").getData()).containsEntry("status", "MISMATCH");
        assertThat(service.openStatus(8L, "test-only-original-key", "usdt30d", "20").getData()).containsEntry("status", "NOT_FOUND");
        assertThat(service.openStatus(USER, "missing", "usdt30d", "20").getData()).containsEntry("status", "NOT_FOUND");
        row.setIsDeleted(1);
        assertThat(service.openStatus(USER, "test-only-original-key", "usdt30d", "20").getData()).containsEntry("status", "NOT_FOUND");
        row.setIsDeleted(0); row.setResponseJson(null);
        assertThat(service.openStatus(USER, "test-only-original-key", "usdt30d", "20").getData()).containsEntry("status", "UNKNOWN");
        verify(records, times(3)).selectCurrent(SCOPE, "test-only-original-key");
        verify(records).selectCurrent("APP:G1_STAKING_OPEN:USER:8", "test-only-original-key");
        verify(records).selectCurrent(SCOPE, "missing");
        verifyNoMoreInteractions(records);
        verifyNoInteractions(mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void rejectsUnboundedRepresentationsAndOutOfStorageRangeBeforeAnyRead() {
        environment.setActiveProfiles("prod");
        for (String amount : List.of("1e2147483647", "1e-2147483647", "1e2147483648",
                "1e-19", "1000000000000", "999999999999.9999995", "0.0000001",
                "0.123456789012345678901234567890123", "1".repeat(65), "NaN", "")) {
            assertThatThrownBy(() -> service.openStatus(USER, "key", "usdt30d", amount))
                    .as(amount).hasMessageContaining("STAKING_AMOUNT_INVALID");
        }
        // Key validation comes before even parsing a dangerous exponent.
        assertThatThrownBy(() -> service.openStatus(USER, "k".repeat(129), "usdt30d", "1e2147483647"))
                .hasMessageContaining("IDEMPOTENCY_KEY_INVALID");
        verifyNoInteractions(records, mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void controllerAndRealServiceRejectExtremeQueryBeforeParsingOrHashingIt() throws Exception {
        environment.setActiveProfiles("prod");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ffdd.opsconsole.market.web.AppStakingController(service))
                .setControllerAdvice(new ffdd.opsconsole.shared.exception.GlobalExceptionHandler(audit)).build();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                USER.toString(), null, List.of());
        auth.setDetails(java.util.Map.of("subjectType", "USER"));
        for (String amount : List.of("1e2147483647", "1e-2147483647", "1".repeat(65),
                "999999999999.9999995")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/stakes/open-status").principal(auth)
                    .header("Idempotency-Key", "key").param("tierKey", "usdt30d").param("amountUsdt", amount))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.message").value("STAKING_AMOUNT_INVALID"));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/stakes/open-status").principal(auth)
                .header("Idempotency-Key", "k".repeat(129)).param("tierKey", "usdt30d").param("amountUsdt", "1e2147483647"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value("IDEMPOTENCY_KEY_INVALID"));
        verifyNoInteractions(records, mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void boundedHistoricalAmountsAndOriginalKeyLimitRemainQueryableWithoutCurrentGates() {
        environment.setActiveProfiles("prod");
        var row = new AdminIdempotencyRecordEntity();
        row.setIsDeleted(0); row.setStatus("FAILED"); row.setRequestHash(HASH);
        when(records.selectCurrent(SCOPE, "k".repeat(128))).thenReturn(row);
        for (String amount : List.of("20", "2e1", "20.000000000000000000", "20.0000001")) {
            assertThat(service.openStatus(USER, " " + "k".repeat(128) + " ", "usdt30d", amount).getData())
                    .containsEntry("status", "FAILED");
        }
        assertThat(service.openStatus(USER, "maximum", "usdt30d", "999999999999.999999").getData())
                .containsEntry("status", "NOT_FOUND");
        assertThat(service.openStatus(USER, "minimum", "usdt30d", "0.000001").getData())
                .containsEntry("status", "NOT_FOUND");
        verify(records, times(4)).selectCurrent(SCOPE, "k".repeat(128));
        verify(records).selectCurrent(SCOPE, "maximum");
        verify(records).selectCurrent(SCOPE, "minimum");
        verifyNoMoreInteractions(records);
        verifyNoInteractions(mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void invalidIdentityAmountKeyAndNonProductionProfileNeverReachAnyDataSurface() {
        environment.setActiveProfiles("dev");
        assertThatThrownBy(() -> service.openStatus(null, "key", "usdt30d", "1")).hasMessageContaining("USER_AUTH_REQUIRED");
        for (String amount : java.util.Arrays.asList(null, "0", "-1")) {
            assertThatThrownBy(() -> service.openStatus(USER, "key", "usdt30d", amount)).hasMessageContaining("STAKING_AMOUNT_INVALID");
        }
        assertThatThrownBy(() -> service.openStatus(USER, "", "usdt30d", "1")).hasMessageContaining("IDEMPOTENCY_KEY_REQUIRED");
        environment.setActiveProfiles("test");
        assertThatThrownBy(() -> service.openStatus(USER, "key", "usdt30d", "1")).hasMessageContaining("STAKING_PROFILE_INVALID");
        verifyNoInteractions(records, mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }

    @Test
    void historicalTierNormalizationAndFixedTierBoundaryDoNotReadCurrentProducts() {
        environment.setActiveProfiles("prod");
        var row = new AdminIdempotencyRecordEntity();
        row.setIsDeleted(0); row.setStatus("FAILED"); row.setRequestHash(HASH);
        when(records.selectCurrent(SCOPE, "key")).thenReturn(row);
        assertThat(service.openStatus(USER, "key", " USDT_30D ", "2e1").getData())
                .containsEntry("status", "FAILED");
        for (String tier : java.util.Arrays.asList(null, "", "usdt7d", "x".repeat(33))) {
            assertThatThrownBy(() -> service.openStatus(USER, "key", tier, "20"))
                    .hasMessageContaining("STAKING_TIER_INVALID");
        }
        verify(records).selectCurrent(SCOPE, "key");
        verifyNoMoreInteractions(records);
        verifyNoInteractions(mapper, sandbox, disclosure, config, earnings, outbox, audit, expiry, fundAdapter);
    }
}
