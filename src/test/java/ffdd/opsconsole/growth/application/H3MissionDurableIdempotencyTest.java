package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GrowthMissionEditRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class H3MissionDurableIdempotencyTest {
    private final AdminIdempotencyRecordMapper recordMapper = mock(AdminIdempotencyRecordMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AtomicReference<AdminIdempotencyRecordEntity> stored = new AtomicReference<>();
    private final AdminIdempotencyTransactionExecutor transactionExecutor =
            new AdminIdempotencyTransactionExecutor(
                    recordMapper,
                    new ObjectMapper().findAndRegisterModules(),
                    mock(AdminIdempotencyExpiryTransitionExecutor.class));
    private final AdminIdempotencyService idempotency = new AdminIdempotencyService(
            transactionExecutor,
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
    private final OpsGrowthCommandBoundary boundary = new OpsGrowthCommandBoundary(idempotency, outbox);

    @BeforeEach
    void persistIdempotencyRecordsInMemory() {
        when(recordMapper.selectActive(anyString(), anyString())).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity record = stored.get();
            if (record == null || !invocation.getArgument(0).equals(record.getScope())
                    || !invocation.getArgument(1).equals(record.getIdempotencyKey())
                    || !Integer.valueOf(0).equals(record.getIsDeleted())) {
                return null;
            }
            return record;
        });
        when(recordMapper.selectCurrent(anyString(), anyString())).thenReturn(null);
        when(recordMapper.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity record = invocation.getArgument(0);
            record.setId(501L);
            stored.set(record);
            return 1;
        });
        when(recordMapper.markSucceeded(any(), anyString())).thenAnswer(invocation -> {
            AdminIdempotencyRecordEntity record = stored.get();
            record.setStatus("SUCCEEDED");
            record.setResponseJson(invocation.getArgument(1));
            return 1;
        });
        when(recordMapper.markFailed(any(), anyString())).thenReturn(1);
    }

    @Test
    void sameKeyAndPayloadReplaysStoredMissionResponseWithoutSecondCas() {
        GrowthMissionEditRequest request = new GrowthMissionEditRequest(
                "MISSION", "Renamed", "Original", "approved rename", "superadmin");
        AtomicInteger casCalls = new AtomicInteger();

        ApiResult<Map<String, Object>> first = executeEdit("idem-h3-edit", request, casCalls);
        ApiResult<Map<String, Object>> replay = executeEdit("idem-h3-edit", request, casCalls);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getData()).containsEntry("name", "Renamed");
        assertThat(casCalls).hasValue(1);
        assertThat(stored.get().getScope()).isEqualTo("H3_MISSION:EDIT:MISSION:H3_TASK");
        verify(outbox, times(1)).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sameKeyWithDifferentMissionPayloadIsRejectedBeforeSecondCas() {
        AtomicInteger casCalls = new AtomicInteger();
        executeEdit("idem-h3-edit", new GrowthMissionEditRequest(
                "MISSION", "Renamed", "Original", "approved rename", "superadmin"), casCalls);

        assertThatThrownBy(() -> executeEdit("idem-h3-edit", new GrowthMissionEditRequest(
                "MISSION", "Different", "Original", "different rename", "superadmin"), casCalls))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        assertThat(casCalls).hasValue(1);
    }

    private ApiResult<Map<String, Object>> executeEdit(
            String key,
            GrowthMissionEditRequest request,
            AtomicInteger casCalls) {
        return boundary.execute("H3", "MISSION_EDIT", "H3_TASK", key, request, () -> {
            casCalls.incrementAndGet();
            return ApiResult.ok(Map.of("name", request.name()));
        });
    }
}
