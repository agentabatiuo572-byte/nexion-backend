package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.dto.OutboxDiagnosticsView;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class A4OutboxDiagnosticsService {
    private final A4OutboxDiagnosticsMapper mapper;

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
        return new OutboxDiagnosticsView(summary.total(), summary.unresolved(), Math.max(0, summary.oldestSeconds()),
                groups.size() > 200, groups.stream().limit(200).map(g -> new OutboxDiagnosticsView.Group(
                        safeType(g.eventType()), safeStatus(g.safeStatus()), g.count(), g.oldestAt(), g.unresolved())).toList(),
                visible.stream().map(r -> new OutboxDiagnosticsView.Event(safeId(r.eventId()), safeType(r.eventType()),
                        safeStatus(r.status()), r.retryCount(), r.createdAt(), r.nextRetryAt(), r.errorCode(),
                        r.auditLinkUnresolved(), List.copyOf(receipts.getOrDefault(r.eventId(), List.of())))).toList(),
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
}
