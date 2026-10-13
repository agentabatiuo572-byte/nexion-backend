package ffdd.opsconsole.device.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Internal current-device evidence; the caller authorizes the complete customer scope and owns its snapshot. */
public interface SupportDeviceReadFacade extends DomainFacade {
    Snapshot readCurrent(Collection<Long> customerIds);
    FundEarnings readFundEarnings(Collection<Long> customerIds);
    Map<String,Integer> timePrecisions();
    record FundEarnings(List<Map<String,Object>> receipts,List<Map<String,Object>> tasks,
        List<Map<String,Object>> ledgers,List<Map<String,Object>> earningEvents) {
        public FundEarnings {receipts=copy(receipts);tasks=copy(tasks);ledgers=copy(ledgers);earningEvents=copy(earningEvents);}
        private static List<Map<String,Object>> copy(List<Map<String,Object>> rows){return rows.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();}
    }

    enum ConnectionStatus { ONLINE, OFFLINE, UNKNOWN, NOT_APPLICABLE }

    /** Unknown holding rows are separate from proven stock, not silently counted as zero. */
    record Snapshot(List<DeviceEvidence> devices, List<DeviceEvidence> unknownHoldingDevices,
                    LocalDateTime evaluatedDbAt) {
        public Snapshot {
            devices = List.copyOf(devices);
            unknownHoldingDevices = List.copyOf(unknownHoldingDevices);
        }
    }

    /** Source channel is provenance, never a paid/free classification. No customer activity is inferred. */
    record DeviceEvidence(long deviceId, long customerId, String sourceOrderNo, String sourceChannel,
        String deviceType, BigDecimal hashrate, String ownershipStatus, String lifecycleStatus,
        LocalDateTime activatedAt, LocalDateTime deactivatedAt, int pendingDeactivate,
        String sourceEnvironment, String runId, RuntimeEvidence runtime, ConnectionStatus connectionStatus) { }

    /** Existing pause/task fields are raw current evidence, not completed maintenance or due-date proof. */
    record RuntimeEvidence(Long runtimeId, String reportedStatus, LocalDateTime heartbeatAt,
        String pausedReason, String activeTaskNo, Integer networkReachable, LocalDateTime updatedAt) { }
}
