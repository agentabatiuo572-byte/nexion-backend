package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.market.mapper.AppMarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class AppExchangeRecoveryTest {
    private static final AppExchangeService.SwapRequest REQUEST =
            new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), true);
    private final AppExchangeMapper orders = mock(AppExchangeMapper.class);
    private final AdminIdempotencyRecordMapper receipts = mock(AdminIdempotencyRecordMapper.class);
    private final AdminIdempotencyExpiryTransitionExecutor expiry = mock(AdminIdempotencyExpiryTransitionExecutor.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final G2ExchangeFeeAllocationService fees = mock(G2ExchangeFeeAllocationService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AdminIdempotencyService idempotency = new AdminIdempotencyService(
            new AdminIdempotencyTransactionExecutor(receipts, json, expiry), Clock.systemUTC());

    private AppExchangeService service(MockEnvironment env, Optional<AppMarketSandboxService> sandbox) {
        return new AppExchangeService(orders, config, idempotency, outbox, audit, fees, json,
                Clock.systemUTC(), env, sandbox);
    }

    private AdminIdempotencyRecordEntity receipt(String state, String payload) throws Exception {
        var row = new AdminIdempotencyRecordEntity();
        row.setIsDeleted(0);
        row.setRequestHash(hash("NormalizedSwap[fromAsset=USDT, fromAmount=20.000000, queueIfCapped=true]"));
        row.setStatus(state);
        row.setResponseJson(payload);
        row.setExpiresAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(receipts.selectCurrent("APP:G2_SWAP:USER:7", "original-key")).thenReturn(row);
        when(orders.userSandbox(7L)).thenReturn(0);
        return row;
    }

    @Test
    void expiredSuccessReturnsExactCurrentOrderWithoutHistoricalWalletOrAnyWrites() throws Exception {
        receipt("SUCCEEDED", "{\"code\":0,\"data\":{\"wallet\":{\"usdtAvailable\":999999},\"order\":{\"exchangeNo\":\"EX-original\",\"status\":\"QUEUED\"}}}");
        var current = new AppExchangeMapper.ExchangeRow("EX-original", "USDT", "NEX", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("0.2"), "CANCELLED", LocalDateTime.now());
        when(orders.recoveryOrder(7L, "EX-original")).thenReturn(current);
        var result = service(new MockEnvironment(), Optional.empty()).recovery(7L, "original-key",
                new AppExchangeService.SwapRequest(" usdt-to-nex ", new BigDecimal("20.0000009"), true)).getData();
        assertThat(result).containsOnlyKeys("status", "order", "sourceEnvironment", "runId")
                .containsEntry("status", "SUCCEEDED").containsEntry("order", current)
                .containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        verify(orders).userSandbox(7L);
        verify(orders).recoveryOrder(7L, "EX-original");
        verify(receipts).selectCurrent("APP:G2_SWAP:USER:7", "original-key");
        verifyNoMoreInteractions(orders, receipts);
        verifyNoInteractions(config, outbox, audit, fees, expiry);
    }

    @ParameterizedTest
    @ValueSource(strings={"FAILED", "PROCESSING", "UNKNOWN"})
    void nonSuccessNeverParsesOrExposesResponseOrReclaimsKey(String state) throws Exception {
        receipt(state, "not valid json");
        var result = service(new MockEnvironment(), Optional.empty()).recovery(7L, "original-key", REQUEST).getData();
        assertThat(result).containsEntry("status", state).doesNotContainKeys("order", "wallet");
        verify(orders).userSandbox(7L);
        verify(receipts).selectCurrent("APP:G2_SWAP:USER:7", "original-key");
        verifyNoMoreInteractions(orders, receipts);
        verifyNoInteractions(expiry, config, outbox, audit, fees);
    }

    @ParameterizedTest
    @ValueSource(strings={"not-json", "null", "{}", "{\"code\":409,\"data\":{\"order\":{\"exchangeNo\":\"EX-other\"}}}",
            "{\"code\":0,\"data\":{\"order\":{\"exchangeNo\":7}}}"})
    void malformedSuccessCannotBecomeAReceipt(String payload) throws Exception {
        receipt("SUCCEEDED", payload);
        assertThat(service(new MockEnvironment(), Optional.empty()).recovery(7L, "original-key", REQUEST).getData())
                .containsEntry("status", "UNKNOWN").doesNotContainKey("order");
        verify(orders).userSandbox(7L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(expiry, config, outbox, audit, fees);
    }

    @Test
    void changedIntentDeletedMissingAndDifferentAccountCannotRecoverAnOrder() throws Exception {
        var row = receipt("SUCCEEDED", "{\"code\":0,\"data\":{\"order\":{\"exchangeNo\":\"EX-original\"}}}");
        var svc = service(new MockEnvironment(), Optional.empty());
        assertThat(svc.recovery(7L, "original-key", new AppExchangeService.SwapRequest("USDT_TO_NEX", BigDecimal.ONE, true)).getData())
                .containsEntry("status", "MISMATCH").doesNotContainKey("order");
        row.setIsDeleted(1);
        assertThat(svc.recovery(7L, "original-key", REQUEST).getData()).containsEntry("status", "NOT_FOUND");
        when(orders.userSandbox(8L)).thenReturn(0);
        assertThat(svc.recovery(8L, "original-key", REQUEST).getData()).containsEntry("status", "NOT_FOUND");
        row.setIsDeleted(0);
        assertThat(svc.recovery(7L, "original-key", REQUEST).getData()).containsEntry("status", "UNKNOWN");
        verify(orders).recoveryOrder(7L, "EX-original");
        verify(orders, never()).recoveryOrder(8L, "EX-original");
        verifyNoInteractions(expiry, config, outbox, audit, fees);
    }

    @Test
    void sandboxUsesOnlyCurrentRunAccountKeyAndReturnsProjectedOrder() throws Exception {
        var env = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "recovery-run-1");
        env.setActiveProfiles("test");
        var sandboxMapper = mock(AppMarketSandboxMapper.class);
        when(sandboxMapper.userSandbox(7L)).thenReturn(1);
        when(sandboxMapper.exchangeRecoveryByKey("recovery-run-1", 7L, "original-key")).thenReturn(
                new AppMarketSandboxMapper.ExchangeOrder(99L,"recovery-run-1",7L,"EX-sandbox","original-key",
                        hash("USDT:2E+1:true"),"USDT","NEX",new BigDecimal("20"),BigDecimal.TEN,BigDecimal.ONE,
                        "CANCELLED",LocalDateTime.now()));
        var svc = service(env, Optional.of(new AppMarketSandboxService(sandboxMapper, env, Optional.empty())));
        var result = svc.recovery(7L, "original-key", REQUEST).getData();
        assertThat(result).containsEntry("status", "SUCCEEDED").containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "recovery-run-1").doesNotContainKeys("wallet", "caps");
        assertThat(((Map<?,?>)result.get("order")).containsKey("idempotencyKey")).isFalse();
        assertThat(svc.recovery(7L, "original-key", new AppExchangeService.SwapRequest("USDT_TO_NEX", BigDecimal.ONE, true)).getData())
                .containsEntry("status", "MISMATCH").doesNotContainKey("order");
        env.setProperty("NEXION_ACCEPTANCE_RUN_ID", "recovery-run-2");
        assertThat(svc.recovery(7L, "original-key", REQUEST).getData()).containsEntry("status", "NOT_FOUND");
        verify(sandboxMapper, times(3)).userSandbox(7L);
        verify(sandboxMapper, times(2)).exchangeRecoveryByKey("recovery-run-1", 7L, "original-key");
        verify(sandboxMapper).exchangeRecoveryByKey("recovery-run-2", 7L, "original-key");
        verifyNoMoreInteractions(sandboxMapper);
        verifyNoInteractions(orders, receipts, expiry, config, outbox, audit, fees);
    }

    @Test
    void sandboxUserCannotReadCanonicalAndMissingSandboxServiceCannotFallThrough() {
        when(orders.userSandbox(7L)).thenReturn(1);
        assertThatThrownBy(() -> service(new MockEnvironment(), Optional.empty()).recovery(7L,"original-key",REQUEST))
                .hasMessage("EXCHANGE_PRODUCTION_USER_REQUIRED");
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        assertThatThrownBy(() -> service(env, Optional.empty()).recovery(7L,"original-key",REQUEST))
                .hasMessage("EXCHANGE_SANDBOX_ISOLATED_TABLE_UNAVAILABLE");
        verifyNoInteractions(receipts, expiry, config, outbox, audit, fees);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
