package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class AppGenesisRetainedCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"LIST", "CANCEL_LIST", "SECONDARY_BUY", "PRIMARY_PURCHASE"})
    void expiredSuccessfulCommandIsNeverExecutedAgain(String operation) throws Exception {
        var records = mock(AdminIdempotencyRecordMapper.class);
        var mapper = mock(AppGenesisMapper.class);
        when(mapper.userSandbox(42L)).thenReturn(0);
        Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);
        var executor = new AdminIdempotencyTransactionExecutor(records, new ObjectMapper(), mock(AdminIdempotencyExpiryTransitionExecutor.class));
        var guard = new AdminIdempotencyService(executor, clock);
        var service = new AppGenesisService(mapper, mock(PlatformConfigFacade.class), guard,
                mock(EventOutboxService.class), mock(AuditLogService.class), clock, mock(GenesisCatalogService.class),
                new MockEnvironment(), Optional.empty());
        String scope = "APP:G4_GENESIS_" + operation + (operation.equals("PRIMARY_PURCHASE") ? "" : ":HOLDING-42") + ":USER:42";
        String request = operation.equals("CANCEL_LIST") ? "HOLDING-42" : operation.equals("PRIMARY_PURCHASE") ? "1" : "10.000000";
        var row = new AdminIdempotencyRecordEntity();
        row.setId(1L); row.setScope(scope); row.setIdempotencyKey("expired-key"); row.setIsDeleted(0);
        row.setRequestHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8))));
        row.setStatus("SUCCEEDED"); row.setExpiresAt(LocalDateTime.of(2020,1,1,0,0));
        row.setResponseJson("{\"code\":0,\"message\":\"ok\",\"data\":{\"historic\":true}}");
        when(records.selectCurrent(scope, "expired-key")).thenReturn(row);
        var result = switch (operation) {
            case "LIST" -> service.list(42L, "HOLDING-42", "expired-key", new AppGenesisService.ListingRequest(BigDecimal.TEN));
            case "CANCEL_LIST" -> service.cancel(42L, "HOLDING-42", "expired-key");
            case "SECONDARY_BUY" -> service.buyListing(42L, "HOLDING-42", "expired-key", new AppGenesisService.BuyRequest(BigDecimal.TEN));
            default -> service.purchase(42L, "expired-key", new AppGenesisService.PurchaseRequest(1));
        };
        assertThat(result.getData()).containsEntry("historic", true);
        verify(records, never()).resetExpiredById(any(), any(), any());
        verify(records, never()).insert(any(AdminIdempotencyRecordEntity.class));
        verify(mapper).userSandbox(42L);
        verifyNoMoreInteractions(mapper);
    }
}
