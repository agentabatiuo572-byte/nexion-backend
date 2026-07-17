package ffdd.opsconsole.risk.facade;

/** K/B-domain ingress used by the J3 event projection; implementations must be idempotent by eventId. */
public interface RiskTamperSignalFacade {
    TamperProjectionResult recordTamperSignal(
            String eventId,
            Long userId,
            String userNo,
            String tamperPath,
            String attackEffect,
            String blockedAtEndpoint,
            int eventCount,
            boolean feedK4);

    TamperRadarSnapshot tamperRadarSnapshot(java.time.LocalDateTime since);

    record TamperProjectionResult(boolean k4Accepted, int k4Delta, boolean b5Accepted) {
    }

    record TamperRadarSnapshot(long signalCount, long accountCount, String latestAt) {
        public static TamperRadarSnapshot empty() {
            return new TamperRadarSnapshot(0, 0, "");
        }
    }
}
