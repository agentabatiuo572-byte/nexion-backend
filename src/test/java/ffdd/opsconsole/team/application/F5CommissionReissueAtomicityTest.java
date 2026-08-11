package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.dto.F5CommissionReissueRequest;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class F5CommissionReissueAtomicityTest {
    private F5CommissionMapper mapper;
    private AdminIdempotencyService idempotencyService;
    private F5CommissionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(F5CommissionMapper.class);
        idempotencyService = mock(AdminIdempotencyService.class);
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.findReissueOperationForUpdate(any())).thenReturn(null);
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(4);
                    return action.get();
                });
        TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("1.20"), new BigDecimal("0.85")));
        service = new F5CommissionService(
                mapper,
                config,
                coverage,
                mock(TreasuryLedgerPostingFacade.class),
                mock(AuditLogService.class),
                mock(EventOutboxService.class),
                idempotencyService);
        A2ReplayContext.enterReplay("A2-F5-ATOMIC-TEST");
    }

    @AfterEach
    void tearDown() {
        A2ReplayContext.exitReplay();
    }

    @Test
    void laterMissingSourceFailsBeforeTheBatchWritesAnyPrefix() {
        when(mapper.findEventForUpdate(41L)).thenReturn(source(41L));
        when(mapper.findEventForUpdate(42L)).thenReturn(null);
        when(mapper.insertReissueFromOriginal(eq(41L), anyString(), eq(30), anyString())).thenReturn(1);
        when(mapper.selectLastInsertId()).thenReturn(141L);

        assertThatThrownBy(() -> reissue("idem-prefix", "CM-41", "CM-42"))
                .isInstanceOf(BizException.class)
                .hasMessage("COMMISSION_REISSUE_SOURCE_NOT_FOUND:CM-42");

        verify(mapper, never()).insertReissueFromOriginal(any(), anyString(), eq(30), anyString());
        verify(mapper, never()).insertOperation(
                anyString(), anyString(), any(), any(), any(), anyString(), any(), anyString(),
                any(), anyString(), anyString(), anyString());
    }

    @Test
    void operationEvidenceWriteFailureAbortsInsteadOfReturningSuccess() {
        when(mapper.findEventForUpdate(41L)).thenReturn(source(41L));
        when(mapper.insertReissueFromOriginal(eq(41L), anyString(), eq(30), anyString())).thenReturn(1);
        when(mapper.selectLastInsertId()).thenReturn(141L);
        when(mapper.insertOperation(
                anyString(), eq("REISSUE"), eq(41L), eq(141L), eq(900041L), eq("network"),
                eq(new BigDecimal("10.000000")), eq("USDT"), eq(null), anyString(), anyString(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> reissue("idem-operation-write", "CM-41"))
                .isInstanceOf(BizException.class)
                .hasMessage("COMMISSION_REISSUE_OPERATION_WRITE_FAILED:CM-41");
    }

    @Test
    void successfulBatchUsesDistinctBoundedOperationEvidenceKeys() {
        when(mapper.findEventForUpdate(41L)).thenReturn(source(41L));
        when(mapper.findEventForUpdate(42L)).thenReturn(source(42L));
        when(mapper.insertReissueFromOriginal(any(), anyString(), eq(30), anyString())).thenReturn(1);
        when(mapper.selectLastInsertId()).thenReturn(141L, 142L);
        when(mapper.insertOperation(
                anyString(), eq("REISSUE"), any(), any(), any(), anyString(), any(), anyString(),
                any(), anyString(), anyString(), anyString())).thenReturn(1);

        ApiResult<Map<String, Object>> result = reissue("k".repeat(128), "CM-42", "CM-41");

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<String> evidenceKeys = ArgumentCaptor.forClass(String.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertOperation(
                anyString(), eq("REISSUE"), any(), any(), any(), anyString(), any(), anyString(),
                any(), anyString(), anyString(), evidenceKeys.capture());
        assertThat(evidenceKeys.getAllValues())
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key.length()).isLessThanOrEqualTo(128));
        verify(mapper).findEventForUpdate(41L);
        verify(mapper).findEventForUpdate(42L);
    }

    private ApiResult<Map<String, Object>> reissue(String idempotencyKey, String... ids) {
        return service.reissue(
                idempotencyKey,
                new F5CommissionReissueRequest(List.of(ids), "verified batch remediation", "operator"));
    }

    private Map<String, Object> source(long id) {
        return Map.of(
                "eventId", id,
                "rawStatus", "REVERSED",
                "amount", new BigDecimal("10.000000"),
                "userId", 900000L + id,
                "currency", "USDT",
                "kind", "network");
    }
}
