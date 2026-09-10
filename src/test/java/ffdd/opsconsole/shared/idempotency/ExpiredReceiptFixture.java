package ffdd.opsconsole.shared.idempotency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory repository boundary exercising the real claim/replay/expiry executor. No business DB. */
public final class ExpiredReceiptFixture {
    public final AdminIdempotencyRecordMapper records = mock(AdminIdempotencyRecordMapper.class);
    public final AdminIdempotencyService service;
    private final Map<Long, AdminIdempotencyRecordEntity> rows = new LinkedHashMap<>();
    private LocalDateTime databaseNow = LocalDateTime.of(2026, 9, 6, 0, 0);

    public ExpiredReceiptFixture() {
        when(records.selectCurrent(anyString(), anyString())).thenAnswer(call -> find(call.getArgument(0), call.getArgument(1)));
        when(records.selectActive(anyString(), anyString())).thenAnswer(call -> {
            AdminIdempotencyRecordEntity row = find(call.getArgument(0), call.getArgument(1));
            return row != null && row.getIsDeleted() == 0 && row.getExpiresAt().isAfter(databaseNow) ? row : null;
        });
        when(records.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(call -> {
            AdminIdempotencyRecordEntity row = call.getArgument(0);
            row.setId((long) rows.size() + 1); rows.put(row.getId(), row); return 1;
        });
        when(records.markSucceeded(anyLong(), anyString())).thenAnswer(call -> {
            AdminIdempotencyRecordEntity row = rows.get(call.getArgument(0, Long.class));
            row.setStatus("SUCCEEDED"); row.setResponseJson(call.getArgument(1)); return 1;
        });
        when(records.resetExpiredById(anyLong(), anyString(), any())).thenAnswer(call -> {
            AdminIdempotencyRecordEntity row = rows.get(call.getArgument(0, Long.class));
            if (row.getExpiresAt().isAfter(databaseNow)) return 0;
            row.setStatus("PROCESSING"); row.setRequestHash(call.getArgument(1));
            row.setResponseJson(null); row.setExpiresAt(call.getArgument(2)); return 1;
        });
        service = new AdminIdempotencyService(new AdminIdempotencyTransactionExecutor(records,
                new ObjectMapper().findAndRegisterModules(), mock(AdminIdempotencyExpiryTransitionExecutor.class)),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));
    }

    public void advanceBeyondLease() { databaseNow = databaseNow.plusDays(2); }

    private AdminIdempotencyRecordEntity find(String scope, String key) {
        return rows.values().stream().filter(row -> scope.equals(row.getScope()) && key.equals(row.getIdempotencyKey()))
                .findFirst().orElse(null);
    }
}
