package ffdd.opsconsole.device.compute.v2;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Durable lifecycle envelope. Money is an exact number of millionths; no secrets or full artifacts. */
public record ComputeV2Event(int schemaVersion, String eventId, Kind kind, String deploymentScope,
        String sourceEnvironment, String runId, long userId, long deviceId, String instanceNo,
        String taskNo, long generation, Instant occurredAt, TaskSnapshot task, Verified evidence, String reason) {
    public enum Kind { ISSUED, STARTED, VERIFIED, FAILED, FENCED, EXITED }

    public ComputeV2Event {
        if (schemaVersion != 2 || userId <= 0 || deviceId <= 0 || generation <= 0)
            throw new IllegalArgumentException("Invalid compute event identity");
        text(eventId, 192); text(deploymentScope, 96); text(instanceNo, 96); text(taskNo, 96);
        if (!"PRODUCTION".equals(sourceEnvironment) || !"".equals(runId))
            throw new IllegalArgumentException("Compute v2 requires explicit production ownership");
        Objects.requireNonNull(kind); Objects.requireNonNull(occurredAt); Objects.requireNonNull(task);
        if (!eventId.equals(eventId(taskNo, generation, kind)))
            throw new IllegalArgumentException("Noncanonical compute event id");
        if (kind == Kind.VERIFIED) {
            Objects.requireNonNull(evidence, "Verified evidence required");
            if (task.startedAt() == null || evidence.completedAt().isBefore(task.startedAt())
                    || evidence.verifiedAt().isBefore(evidence.completedAt())
                    || occurredAt.isBefore(evidence.verifiedAt())
                    || !task.inputSha256().equals(evidence.inputSha256()))
                throw new IllegalArgumentException("Invalid completion chronology or input identity");
            String daily = "CLOUD_SHARE_DAILY:" + deviceId + ":" + evidence.utcDay();
            if (evidence.rewardNexMicros() > 0 && !daily.equals(evidence.dailyEntitlementKey()))
                throw new IllegalArgumentException("Invalid device UTC daily entitlement");
        } else if (evidence != null) throw new IllegalArgumentException("Evidence only belongs to VERIFIED");
        if (reason != null && reason.length() > 512) throw new IllegalArgumentException("Reason too long");
    }

    public static String eventId(String taskNo, long generation, Kind kind) {
        return taskNo + ":" + generation + ":" + kind;
    }

    public String receiptNo() { return "CTR-" + taskNo.substring(Math.max(0, taskNo.length() - 32)); }

    public record TaskSnapshot(String requestId, String taskConfigId, String taskName, String taskClass,
            String modelName, String clientName, long configVersion, int expectedSeconds, int minSeconds,
            int maxSeconds, int requiredSeconds, int executionTimeoutSec, int taskLockMinutes, Instant issuedAt, Instant startedAt,
            Instant leaseExpiresAt, Instant proofExpiresAt, long rewardUsdtMicros, long rawQuoteMicros,
            BigDecimal e3Multiplier, long walletId, String walletIncarnation, DeviceSnapshot device,
            String inputSha256, String modelSha256, String inputArtifactRef) {
        public TaskSnapshot {
            text(requestId, 192); text(taskConfigId, 64); text(taskName, 128); text(taskClass, 64);
            text(modelName, 128); text(clientName, 128); text(walletIncarnation, 36);
            if (!walletIncarnation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || walletId <= 0 || configVersion < 0 || minSeconds <= 0 || maxSeconds < minSeconds
                    || requiredSeconds < minSeconds || requiredSeconds > maxSeconds || expectedSeconds <= 0
                    || executionTimeoutSec <= 0 || taskLockMinutes != 0 || rewardUsdtMicros < 0 || rawQuoteMicros < 0
                    || e3Multiplier == null || e3Multiplier.signum() < 0)
                throw new IllegalArgumentException("Invalid frozen task quote");
            Objects.requireNonNull(issuedAt); Objects.requireNonNull(leaseExpiresAt);
            Objects.requireNonNull(proofExpiresAt); Objects.requireNonNull(device);
            e3Multiplier=e3Multiplier.stripTrailingZeros();
            hash(inputSha256); hash(modelSha256); text(inputArtifactRef, 512);
            if (!leaseExpiresAt.isAfter(issuedAt) || !proofExpiresAt.isAfter(issuedAt)
                    || startedAt != null && (startedAt.isBefore(issuedAt) || !leaseExpiresAt.isAfter(startedAt) || !proofExpiresAt.isAfter(startedAt)))
                throw new IllegalArgumentException("Invalid task lifetime");
        }

        /** Existing fixtures may omit timeout; production callers pass the frozen configured value explicitly. */
        public TaskSnapshot(String requestId,String taskConfigId,String taskName,String taskClass,
                String modelName,String clientName,long configVersion,int expectedSeconds,int minSeconds,
                int maxSeconds,int requiredSeconds,int taskLockMinutes,Instant issuedAt,Instant startedAt,
                Instant leaseExpiresAt,Instant proofExpiresAt,long rewardUsdtMicros,long rawQuoteMicros,
                BigDecimal e3Multiplier,long walletId,String walletIncarnation,DeviceSnapshot device,
                String inputSha256,String modelSha256,String inputArtifactRef) {
            this(requestId,taskConfigId,taskName,taskClass,modelName,clientName,configVersion,expectedSeconds,
                    minSeconds,maxSeconds,requiredSeconds,Math.max(300,maxSeconds),taskLockMinutes,issuedAt,
                    startedAt,leaseExpiresAt,proofExpiresAt,rewardUsdtMicros,rawQuoteMicros,e3Multiplier,walletId,
                    walletIncarnation,device,inputSha256,modelSha256,inputArtifactRef);
        }

        /** Start is a new durable snapshot; callers must reuse this exact snapshot on retry. */
        public TaskSnapshot withStarted(Instant start, Instant lease, Instant proofExpiry) {
            if (startedAt != null) {
                if (!startedAt.equals(start) || !leaseExpiresAt.equals(lease) || !proofExpiresAt.equals(proofExpiry))
                    throw new IllegalArgumentException("Task start is immutable");
                return this;
            }
            return new TaskSnapshot(requestId, taskConfigId, taskName, taskClass, modelName, clientName,
                    configVersion, expectedSeconds, minSeconds, maxSeconds, requiredSeconds, executionTimeoutSec, taskLockMinutes,
                    issuedAt, start, lease, proofExpiry, rewardUsdtMicros, rawQuoteMicros, e3Multiplier,
                    walletId, walletIncarnation, device, inputSha256, modelSha256, inputArtifactRef);
        }
    }

    public record DeviceSnapshot(String deviceName, String deviceType, String productCode,
            String gpuModel, Integer gpuCount, BigDecimal hashrate, Integer vramTotalGb) {
        public DeviceSnapshot { text(deviceName, 128); text(deviceType, 64); if(hashrate!=null)hashrate=hashrate.stripTrailingZeros(); }
    }

    public record Verified(String proofHash, String inputSha256, String resultSha256, Instant verifiedAt,
            Instant completedAt, String artifactRef, long artifactBytes, long rewardNexMicros,
            String dailyEntitlementKey, LocalDate utcDay) {
        public Verified {
            hash(proofHash); hash(inputSha256); hash(resultSha256); text(artifactRef, 512);
            Objects.requireNonNull(verifiedAt); Objects.requireNonNull(completedAt); Objects.requireNonNull(utcDay);
            if (artifactBytes <= 0 || rewardNexMicros < 0
                    || !completedAt.atZone(ZoneOffset.UTC).toLocalDate().equals(utcDay)
                    || rewardNexMicros > 0 && (dailyEntitlementKey == null || dailyEntitlementKey.isBlank())
                    || rewardNexMicros == 0 && dailyEntitlementKey != null)
                throw new IllegalArgumentException("Invalid verified artifact or daily entitlement");
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("SHA-256 required");
    }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("Bounded nonempty text required");
    }
}
