package ffdd.opsconsole.device.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper.Row;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;

/** The authorized caller owns the read transaction; this evidence reader never creates a transaction. */
@ApplicationService
@RequiredArgsConstructor
public class SupportDeviceReadService implements SupportDeviceReadFacade {
    private static final Set<String> DISPOSED = Set.of("RECYCLED", "RETIRED", "REFUNDED", "TRANSFERRED", "UNBOUND");
    private static final Set<String> RUNNING = Set.of("ONLINE", "BUSY", "RUNNING", "ACTIVE", "OFFLINE");
    private static final Set<String> NOT_STARTED = Set.of("INVENTORY", "PENDING", "PENDING_ACTIVATION");
    private final SupportDeviceReadMapper mapper;

    @Override
    public Snapshot readCurrent(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("INVALID_SUPPORT_DEVICE_SCOPE");
        var scope = new TreeSet<>(customerIds);
        // An authorized empty scope is not a query against all customers.
        if (scope.isEmpty()) return new Snapshot(List.of(), List.of(), null);
        var rows = mapper.readCurrent(List.copyOf(scope));
        if (rows == null) throw new IllegalStateException("INVALID_SUPPORT_DEVICE_ROW");
        // Scope checking precedes every disposal filter and every other row-validation path.
        if (rows.stream().anyMatch(row -> row != null && row.customerId() != null && !scope.contains(row.customerId())))
            throw new IllegalStateException("INVALID_SUPPORT_DEVICE_SCOPE");
        var unique = new TreeMap<Long, Row>();
        LocalDateTime evaluatedAt = null;
        for (var row : rows) {
            validate(row);
            if (evaluatedAt == null) evaluatedAt = row.evaluatedDbAt();
            else if (!evaluatedAt.equals(row.evaluatedDbAt()))
                throw new IllegalStateException("INVALID_SUPPORT_DEVICE_OBSERVATION_TIME");
            var previous = unique.putIfAbsent(row.deviceId(), row);
            if (previous != null && !previous.equals(row))
                throw new IllegalStateException("CONFLICTING_SUPPORT_DEVICE_ROW");
        }
        if (evaluatedAt == null) {
            evaluatedAt = mapper.currentDbTime();
            if (evaluatedAt == null) throw new IllegalStateException("INVALID_SUPPORT_DEVICE_OBSERVATION_TIME");
        }
        var held = new ArrayList<DeviceEvidence>();
        var unknown = new ArrayList<DeviceEvidence>();
        for (var row : unique.values()) {
            var ownership = normalized(row.ownershipStatus());
            var lifecycle = normalized(row.lifecycleStatus());
            if (DISPOSED.contains(ownership) || DISPOSED.contains(lifecycle)) continue;
            var evidence = evidence(row, "OWNED".equals(ownership) ? connection(row) : ConnectionStatus.UNKNOWN);
            if ("OWNED".equals(ownership)) held.add(evidence);
            else unknown.add(evidence);
        }
        return new Snapshot(held, unknown, evaluatedAt);
    }

    private static void validate(Row row) {
        if (row == null || row.deviceId() == null || row.deviceId() <= 0 || row.customerId() == null
                || row.customerId() <= 0 || row.hashrate() == null || row.hashrate().signum() < 0
                || row.pendingDeactivate() == null || (row.pendingDeactivate() != 0 && row.pendingDeactivate() != 1)
                || row.evaluatedDbAt() == null || row.heartbeatFresh() == null
                || (row.heartbeatFresh() != 0 && row.heartbeatFresh() != 1)
                || (row.runtimeId() != null && row.runtimeId() <= 0))
            throw new IllegalStateException("INVALID_SUPPORT_DEVICE_ROW");
    }

    private static ConnectionStatus connection(Row row) {
        var lifecycle = normalized(row.lifecycleStatus());
        // Not applicable to current operation, not an assertion that this hardware can never connect.
        if ((NOT_STARTED.contains(lifecycle) && row.activatedAt() == null)
                || (Set.of("DEACTIVATED", "INACTIVE").contains(lifecycle) && row.deactivatedAt() != null))
            return ConnectionStatus.NOT_APPLICABLE;
        if (!RUNNING.contains(lifecycle) || row.activatedAt() == null || row.deactivatedAt() != null
                || row.runtimeId() == null || row.heartbeatAt() == null
                || row.heartbeatAt().isAfter(row.evaluatedDbAt())) return ConnectionStatus.UNKNOWN;
        return switch (normalized(row.runtimeStatus())) {
            case "ONLINE" -> row.heartbeatFresh() == 1 ? ConnectionStatus.ONLINE : ConnectionStatus.OFFLINE;
            case "OFFLINE" -> ConnectionStatus.OFFLINE;
            default -> ConnectionStatus.UNKNOWN;
        };
    }

    private static DeviceEvidence evidence(Row row, ConnectionStatus status) {
        return new DeviceEvidence(row.deviceId(), row.customerId(), row.sourceOrderNo(), row.sourceChannel(),
            row.deviceType(), row.hashrate(), row.ownershipStatus(), row.lifecycleStatus(), row.activatedAt(),
            row.deactivatedAt(), row.pendingDeactivate(), row.sourceEnvironment(), row.runId(),
            new RuntimeEvidence(row.runtimeId(), row.runtimeStatus(), row.heartbeatAt(), row.pausedReason(),
                row.activeTaskNo(), row.networkReachable(), row.runtimeUpdatedAt()), status);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
