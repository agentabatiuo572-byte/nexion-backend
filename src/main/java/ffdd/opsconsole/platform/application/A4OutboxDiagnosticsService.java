package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.dto.OutboxDeadLetterView;
import ffdd.opsconsole.platform.dto.OutboxDiagnosticsView;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class A4OutboxDiagnosticsService {
    private static final List<DeadLetterType> DEAD_LETTER_TYPES = List.of(
            new DeadLetterType("weekly_t2_browse_store", "H3_STOREFRONT_THREE_PRODUCTS_VIEWED"),
            new DeadLetterType("weekly_t2_genesis_browse", "H3_GENESIS_SECONDARY_MARKET_VIEWED"),
            new DeadLetterType("weekly_t2_ai_jobs_50", "H3_COMPUTE_COMPLETED_50"),
            new DeadLetterType("weekly_t2_invite_friend", "H3_REFERRAL_REGISTERED"),
            new DeadLetterType("weekly_t2_nex_swap", "H3_EXCHANGE_COMPLETED"));
    private final A4OutboxDiagnosticsMapper mapper;
    private final A4UserNumberService userNumbers;

    @Autowired
    public A4OutboxDiagnosticsService(A4OutboxDiagnosticsMapper mapper, A4UserNumberService userNumbers) {
        this.mapper = mapper;
        this.userNumbers = userNumbers;
    }

    /** Compatibility constructor for focused tests that only exercise the mapper projection. */
    A4OutboxDiagnosticsService(A4OutboxDiagnosticsMapper mapper) {
        this(mapper, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 8)
    public OutboxDeadLetterView deadLetters() {
        Map<String, A4OutboxDiagnosticsMapper.DeadLetterRow> rows = Objects.requireNonNull(mapper.deadLetters())
                .stream().collect(Collectors.toMap(A4OutboxDiagnosticsMapper.DeadLetterRow::eventType,
                        row -> row, (first, ignored) -> first));
        List<OutboxDeadLetterView.Type> types = DEAD_LETTER_TYPES.stream().map(definition -> {
            A4OutboxDiagnosticsMapper.DeadLetterRow row = rows.get(definition.eventType());
            return new OutboxDeadLetterView.Type(
                    definition.questCode(),
                    definition.eventType(),
                    row == null ? 0 : row.outboxDeadCount(),
                    row == null ? 0 : row.bothLayersDeadCount(),
                    row == null ? null : row.oldestOutboxLastStateAt(),
                    row == null ? null : row.oldestBothLayersDeadObservedAt(),
                    row == null || row.missingDeliveryDeadAt() == 0);
        }).toList();
        return new OutboxDeadLetterView(types);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 8)
    public OutboxDiagnosticsView read(String eventType, String status, boolean unresolvedOnly, String afterId, int pageSize) {
        if (pageSize < 1 || pageSize > 50 || afterId == null || !afterId.matches("0|[1-9][0-9]{0,18}")) {
            throw new BizException(422, "A4_OUTBOX_FILTER_INVALID");
        }
        long cursor;
        try { cursor = Long.parseLong(afterId); }
        catch (NumberFormatException ex) { throw new BizException(422, "A4_OUTBOX_FILTER_INVALID"); }
        if (eventType != null && !eventType.matches("[A-Za-z][A-Za-z0-9_.-]{0,95}")) {
            throw new BizException(422, "A4_OUTBOX_FILTER_INVALID");
        }
        if (status != null && !Set.of("PENDING", "FAILED", "OTHER").contains(status)) {
            throw new BizException(422, "A4_OUTBOX_FILTER_INVALID");
        }
        var summary = Objects.requireNonNull(mapper.summary());
        var groups = Objects.requireNonNull(mapper.groups());
        var page = Objects.requireNonNull(mapper.page(cursor, eventType, status, unresolvedOnly, pageSize + 1));
        boolean more = page.size() > pageSize;
        var visible = page.stream().limit(pageSize).toList();
        Map<String, List<OutboxDiagnosticsView.Receipt>> receipts = new HashMap<>();
        var ids = visible.stream().map(A4OutboxDiagnosticsMapper.EventRow::eventId)
                .filter(id -> id != null && id.matches("[a-f0-9]{32}")).toList();
        if (!ids.isEmpty()) {
            for (var r : Objects.requireNonNull(mapper.receipts(ids))) {
                // SQL collation can match aliases. Never attach another event's receipt.
                if (ids.contains(r.eventId())) receipts.computeIfAbsent(r.eventId(), k -> new ArrayList<>())
                        .add(new OutboxDiagnosticsView.Receipt(r.status(), r.count()));
            }
        }
        Map<Long, String> visibleUserNumbers = userNumbers == null ? Map.of()
                : userNumbers.userNos(visible.stream().map(A4OutboxDiagnosticsMapper.EventRow::userId).toList());
        return new OutboxDiagnosticsView(summary.total(), summary.unresolved(), Math.max(0, summary.oldestSeconds()),
                groups.size() > 200, groups.stream().limit(200).map(g -> new OutboxDiagnosticsView.Group(
                        safeType(g.eventType()), safeStatus(g.safeStatus()), g.count(), g.oldestAt(), g.unresolved())).toList(),
                visible.stream().map(r -> new OutboxDiagnosticsView.Event(safeId(r.eventId()), safeType(r.eventType()),
                        safeStatus(r.status()), r.retryCount(), r.createdAt(), r.nextRetryAt(), r.errorCode(),
                        r.auditLinkUnresolved(), List.copyOf(receipts.getOrDefault(r.eventId(), List.of())),
                        r.userId() == null ? null : visibleUserNumbers.get(r.userId()))).toList(),
                more ? Long.toString(visible.get(visible.size() - 1).id()) : null, more);
    }

    private String safeType(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,95}") ? value : "INVALID_EVENT_TYPE";
    }
    private String safeId(String value) {
        return value != null && value.matches("[a-f0-9]{32}") ? value : "INVALID_EVENT_ID";
    }
    private String safeStatus(String value) {
        return value != null && Set.of("PENDING", "FAILED").contains(value) ? value : "OTHER";
    }
    private record DeadLetterType(String questCode, String eventType) {}
}
