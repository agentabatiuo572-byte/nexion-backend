package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.dto.F5CommissionExportRequest;
import ffdd.opsconsole.team.dto.F5CommissionExportPayload;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class F5CommissionExportTest {
    private F5CommissionMapper mapper;
    private AuditLogService audit;
    private EventOutboxService outbox;
    private AdminIdempotencyService idempotency;
    private F5CommissionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(F5CommissionMapper.class);
        audit = mock(AuditLogService.class);
        outbox = mock(EventOutboxService.class);
        idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        service = new F5CommissionService(
                mapper,
                mock(PlatformConfigFacade.class),
                mock(TreasuryCoverageFacade.class),
                mock(TreasuryLedgerPostingFacade.class),
                audit,
                outbox,
                idempotency);
    }

    @Test
    void exportReadsEveryServerPageForCurrentFiltersAndReturnsAReplayableRedactedCsv() {
        F5CommissionExportRequest request = new F5CommissionExportRequest(
                "network", "USDT", null, "unlocked", "2026-08", "月度财务复核导出");
        List<Map<String, Object>> firstPage = new ArrayList<>();
        for (int index = 1_001; index >= 2; index--) {
            firstPage.add(event(index, 12_345_678L + index));
        }
        when(mapper.countEvents("network", "USDT", null, "unlocked", "2026-08"))
                .thenReturn(1_001L);
        when(mapper.queryExportEvents("network", "USDT", null, "unlocked", "2026-08", null, 1000))
                .thenReturn(firstPage);
        when(mapper.queryExportEvents("network", "USDT", null, "unlocked", "2026-08", 2L, 1000))
                .thenReturn(List.of(event(1, 12_345_679L)));

        F5CommissionExportPayload payload = service.export("idem-f5-export", request, "auditor-7");

        String csv = new String(payload.content(), StandardCharsets.UTF_8);
        assertThat(payload.rowCount()).isEqualTo(1_001);
        assertThat(payload.byteSize()).isEqualTo(payload.content().length);
        assertThat(payload.sha256()).hasSize(64);
        assertThat(csv).contains("CM-1001", "CM-1", "U***6679");
        assertThat(csv).doesNotContain("12346679");
        assertThat(csv.lines()).hasSize(1_002);
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
        verify(outbox).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void exportRejectsOverTheRowLimitBeforeReadingOrAuditingData() {
        F5CommissionExportRequest request = new F5CommissionExportRequest(
                null, null, null, null, null, "大范围佣金核查导出");
        when(mapper.countEvents(null, null, null, null, null)).thenReturn(50_001L);

        assertThatThrownBy(() -> service.export("idem-too-large", request, "auditor-7"))
                .isInstanceOf(BizException.class)
                .hasMessage("F5_EXPORT_ROW_LIMIT_EXCEEDED");

        verifyNoInteractions(audit, outbox);
    }

    @Test
    void persistedIdempotencyRecordReplaysTheExactBinaryArtifactWithoutASecondExport() {
        AdminIdempotencyRecordMapper recordMapper = mock(AdminIdempotencyRecordMapper.class);
        AtomicReference<AdminIdempotencyRecordEntity> stored = new AtomicReference<>();
        when(recordMapper.selectActive("F5_COMMISSION_EXPORT", "idem-binary-replay"))
                .thenAnswer(ignored -> stored.get());
        when(recordMapper.selectCurrent("F5_COMMISSION_EXPORT", "idem-binary-replay"))
                .thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity record = invocation.getArgument(0);
            record.setId(91L);
            stored.set(record);
            return 1;
        });
        doAnswer(invocation -> {
            AdminIdempotencyRecordEntity record = stored.get();
            record.setStatus("SUCCEEDED");
            record.setResponseJson(invocation.getArgument(1, String.class));
            return 1;
        }).when(recordMapper).markSucceeded(any(), anyString());

        AdminIdempotencyTransactionExecutor transactionExecutor = new AdminIdempotencyTransactionExecutor(
                recordMapper,
                new ObjectMapper(),
                mock(AdminIdempotencyExpiryTransitionExecutor.class));
        AdminIdempotencyService durableIdempotency = new AdminIdempotencyService(
                transactionExecutor,
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
        service = new F5CommissionService(
                mapper,
                mock(PlatformConfigFacade.class),
                mock(TreasuryCoverageFacade.class),
                mock(TreasuryLedgerPostingFacade.class),
                audit,
                outbox,
                durableIdempotency);

        F5CommissionExportRequest request = new F5CommissionExportRequest(
                "network", "USDT", null, "unlocked", "2026-08", "同一请求二进制持久重放核验");
        when(mapper.countEvents("network", "USDT", null, "unlocked", "2026-08")).thenReturn(1L);
        when(mapper.queryExportEvents("network", "USDT", null, "unlocked", "2026-08", null, 1000))
                .thenReturn(List.of(event(1, 12_345_679L)));

        F5CommissionExportPayload first = service.export("idem-binary-replay", request, "auditor-7");
        F5CommissionExportPayload replay = service.export("idem-binary-replay",
                new F5CommissionExportRequest(
                        "network", "USDT", null, "unlocked", "2026-08", "补充说明后稳定重放同一导出文件"),
                "auditor-7");

        assertThat(replay.exportId()).isEqualTo(first.exportId());
        assertThat(replay.filename()).isEqualTo(first.filename());
        assertThat(replay.sha256()).isEqualTo(first.sha256());
        assertThat(replay.content()).containsExactly(first.content());
        verify(mapper, times(1)).countEvents("network", "USDT", null, "unlocked", "2026-08");
        verify(audit, times(1)).recordRequired(any(AuditLogWriteRequest.class));
        verify(outbox, times(1)).publish(anyString(), anyString(), anyString(), any());
    }

    private Map<String, Object> event(long id, long userId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("commissionId", "CM-" + id);
        row.put("eventId", id);
        row.put("userId", userId);
        row.put("kind", "network");
        row.put("currency", "USDT");
        row.put("amount", new BigDecimal("12.34"));
        row.put("sourceUserId", userId + 10);
        row.put("layer", 2);
        row.put("status", "unlocked");
        row.put("settledAt", "2026-08-10 12:00:00");
        return row;
    }
}
