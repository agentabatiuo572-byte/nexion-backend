package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.DeviceRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One short-lived, explicitly TEST executor. This is computation evidence, never commercial attestation. */
@Service
@RequiredArgsConstructor
public class TestComputeWorkerService {
    public static final String KIND = "TEST_DETERMINISTIC_V1";
    public static final String SPEC = "UVEL_TEST_VECTOR_STATS_V1";
    public static final String CLIENT = "UVEL TEST deterministic";
    public static final String TASK_NAME = "UVEL TEST vector statistics v1";
    private static final String PREFIX = "nexion.compute-task.test-worker.";
    private final AppTaskAssignmentMapper mapper;
    private final AuditLogService audit;
    private final Clock clock;
    private final Environment environment;

    // Deliberately contains no credential or credential hash. Safe even through generic audit/principal code.
    public record Grant(String executorId, long ownerId, long deviceId, String instanceNo, String taskNo,
                        String taskConfigId, String runId, long issuedAt, long expiresAt) implements Principal {
        @Override public String getName() { return "test-worker:" + executorId; }
        @Override public String toString() { return getName(); }
    }
    public record CompleteRequest(String specVersion, String inputHash, String resultHash,
                                  String resultArtifactBase64, String proofNonce, Long proofTimestamp) { }
    public record Input(byte[] bytes, String hash, int[] values) { }
    public record Verified(String proofHash, Input input, byte[] result, String resultHash) { }

    public void requireEnabled() {
        if (!"TEST".equals(property("deployment-scope")) || !"true".equals(property("enabled"))) {
            throw new BizException(503, "TEST_COMPUTE_WORKER_DISABLED");
        }
    }

    public Grant authenticate(String authorization) {
        requireEnabled();
        try {
            if (authorization == null || !authorization.matches("Bearer tw1_[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            String encoded = authorization.substring("Bearer tw1_".length());
            byte[] credential = Base64.getUrlDecoder().decode(encoded);
            if (credential.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(credential).equals(encoded)) {
                throw new IllegalArgumentException();
            }
            String hash = property("credential-sha256");
            if (!hash.matches("[a-fA-F0-9]{64}") || !MessageDigest.isEqual(digest(credential), HexFormat.of().parseHex(hash))) {
                throw new IllegalArgumentException();
            }
            Grant grant = configuredGrant();
            requireCurrent(grant);
            return grant;
        } catch (BizException | IllegalArgumentException exception) {
            throw new BizException(401, "TEST_COMPUTE_WORKER_AUTH_INVALID");
        }
    }

    public void requireCurrent(Grant grant) {
        requireEnabled();
        Grant current;
        try { current = configuredGrant(); } catch (RuntimeException badConfig) {
            throw new BizException(401, "TEST_COMPUTE_WORKER_AUTH_INVALID");
        }
        long now = clock.millis();
        if (!current.equals(grant) || now < current.issuedAt() || now >= current.expiresAt()
                || !property("credential-sha256").matches("[a-fA-F0-9]{64}")) {
            throw new BizException(401, "TEST_COMPUTE_WORKER_AUTH_INVALID");
        }
    }

    private Grant configuredGrant() {
        String executor = safe(property("executor-id"), 32);
        if (executor.length() < 3) throw new IllegalArgumentException();
        long owner = positive(property("owner-id")), device = positive(property("device-id"));
        String instance = safe(property("instance-no"), 128), task = safe(property("task-no"), 96);
        String config = safe(property("task-config-id"), 64), run = safe(property("run-id"), 96);
        if (!run.startsWith("TEST")) throw new IllegalArgumentException();
        long issued = positive(property("issued-at")), expires = positive(property("expires-at"));
        if (expires <= issued || expires - issued > 900_000) throw new IllegalArgumentException();
        return new Grant(executor, owner, device, instance, task, config, run, issued, expires);
    }

    public void requireTaskPath(Grant grant, String taskNo) {
        requireCurrent(grant);
        if (!grant.taskNo().equals(taskNo)) throw new BizException(403, "TEST_COMPUTE_WORKER_BINDING_INVALID");
    }

    public void requireFixedKey(Grant grant, String operation, String key) {
        if (!("TEST355-" + operation + "-" + sha256(grant.taskNo().getBytes(StandardCharsets.UTF_8))).equals(key)) {
            throw new BizException(422, "TEST_COMPUTE_WORKER_IDEMPOTENCY_INVALID");
        }
    }

    public String scope(Grant grant, String operation) {
        return "TEST355:" + operation + ":" + sha256((grant.runId() + "\n" + grant.executorId() + "\n"
                + grant.ownerId() + "\n" + grant.deviceId() + "\n" + grant.instanceNo() + "\n" + grant.taskNo()
                + "\n" + grant.taskConfigId() + "\n" + grant.issuedAt() + "\n" + grant.expiresAt()).getBytes(StandardCharsets.UTF_8));
    }

    public String newTaskNo(Grant grant) {
        return "CTA-TEST-" + sha256((grant.runId() + "\n" + grant.executorId() + "\n" + grant.ownerId()
                + "\n" + grant.deviceId() + "\n" + grant.instanceNo() + "\n").getBytes(StandardCharsets.UTF_8))
                .substring(0, 32).toUpperCase(Locale.ROOT);
    }

    public String marker(Grant grant) {
        return "test355-v1:" + sha256((grant.executorId() + "\n" + grant.taskNo()).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public void requireDevice(Grant grant, DeviceRow device) {
        requireCurrent(grant);
        if (device == null || !Objects.equals(device.id(), grant.deviceId())
                || !grant.instanceNo().equals(device.instanceNo()) || !"cloud-share".equals(device.productCode())
                || device.deviceType() == null || !Set.of("SHARE", "CLOUD_SHARE").contains(device.deviceType().toUpperCase(Locale.ROOT))
                || device.activatedAt() == null || device.status() == null || !Set.of("ACTIVE", "ONLINE").contains(device.status().toUpperCase(Locale.ROOT))
                || Boolean.TRUE.equals(device.dispatchPaused()) || hasText(device.pausedReason())
                || mapper.lockPaidCloudShareDailyNex(grant.ownerId(), grant.deviceId()) == null) {
            throw new BizException(403, "TEST_COMPUTE_WORKER_DEVICE_INVALID");
        }
    }

    public void requireTask(Grant grant, AssignmentRow task, LocalDateTime now) {
        requireCurrent(grant);
        if (task == null || !grant.taskNo().equals(task.taskNo()) || !Objects.equals(grant.deviceId(), task.deviceId())
                || !grant.taskConfigId().equals(task.taskId()) || !("CLAIMED".equals(task.status()) || "RUNNING".equals(task.status()))
                || task.requiredSeconds() == null || task.requiredSeconds() < 0 || task.requiredSeconds() > 60
                || task.startedAt() == null || task.pausedAt() != null
                || (task.pausedSeconds() != null && task.pausedSeconds() != 0)
                || task.leaseExpiresAt() == null || !task.leaseExpiresAt().isAfter(now)
                || task.proofExpiresAt() == null || !task.proofExpiresAt().isAfter(now)
                || task.completionNonce() == null || !task.completionNonce().matches("[a-f0-9]{64}")) {
            throw new BizException(409, "TEST_COMPUTE_WORKER_TASK_INVALID");
        }
    }

    public void markOnline(Grant grant, LocalDateTime now) {
        requireCurrent(grant);
        var previous = mapper.lockTestWorkerRuntime(grant.ownerId(), grant.deviceId(), grant.instanceNo());
        if (previous != null && (hasText(previous.pausedReason())
                || !("ONLINE".equals(previous.onlineStatus()) || "OFFLINE".equals(previous.onlineStatus()))
                || (hasText(previous.activeTaskNo()) && !grant.taskNo().equals(previous.activeTaskNo()))
                || ("ONLINE".equals(previous.onlineStatus()) && hasText(previous.agentVersion())
                    && !marker(grant).equals(previous.agentVersion())))) {
            throw new BizException(409, "TEST_COMPUTE_WORKER_RUNTIME_CONFLICT");
        }
        if (previous == null) mapper.insertTestWorkerRuntime(grant.ownerId(), grant.deviceId(), grant.instanceNo(), grant.taskNo(), marker(grant), now);
        else mapper.markTestWorkerOnline(grant.ownerId(), grant.deviceId(), grant.instanceNo(), grant.taskNo(), marker(grant), now);
        requireRuntime(grant, now);
    }

    public void requireRuntime(Grant grant, LocalDateTime now) {
        var runtime = mapper.lockTestWorkerRuntime(grant.ownerId(), grant.deviceId(), grant.instanceNo());
        if (runtime == null || !"ONLINE".equals(runtime.onlineStatus()) || !CLIENT.equals(runtime.clientName())
                || !marker(grant).equals(runtime.agentVersion()) || !grant.taskNo().equals(runtime.activeTaskNo())
                || hasText(runtime.pausedReason()) || runtime.heartbeatAt() == null
                || runtime.heartbeatAt().isAfter(now) || runtime.heartbeatAt().isBefore(now.minusSeconds(120))) {
            throw new BizException(409, "TEST_COMPUTE_WORKER_RUNTIME_STALE");
        }
    }

    public Map<String, Object> claimView(Grant grant, AssignmentRow task) {
        Input input = input(grant, task);
        Map<String, Object> data = bindings(grant);
        data.put("proofNonce", task.completionNonce());
        data.put("proofExpiresAt", epoch(task.proofExpiresAt()));
        data.put("leaseExpiresAt", epoch(task.leaseExpiresAt()));
        data.put("completableAt", epoch(task.startedAt().plusSeconds(task.requiredSeconds())));
        data.put("inputBytesBase64", Base64.getEncoder().encodeToString(input.bytes()));
        data.put("inputHash", input.hash());
        return data;
    }

    public Input input(Grant grant, AssignmentRow task) {
        String seed = "UVEL_TEST_VECTOR_STATS_INPUT_V1\nowner=" + grant.ownerId() + "\ndevice=" + grant.deviceId()
                + "\ninstance=" + grant.instanceNo() + "\ntask=" + grant.taskNo() + "\ntaskConfig=" + grant.taskConfigId()
                + "\nnonce=" + task.completionNonce() + "\ncount=32\n";
        int[] values = new int[32];
        for (int i = 0; i < values.length; i++) {
            byte[] hash = digest((seed + "index=" + i + "\n").getBytes(StandardCharsets.UTF_8));
            values[i] = (((hash[0] & 255) << 8) | (hash[1] & 255)) % 2001 - 1000;
        }
        byte[] bytes = (seed + "values=" + csv(values) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2048) throw new BizException(422, "TEST_COMPUTE_INPUT_INVALID");
        return new Input(bytes, sha256(bytes), values);
    }

    public byte[] expectedResult(Input input) {
        int[] sorted = input.values().clone();
        Arrays.sort(sorted);
        long sum = 0, squares = 0;
        for (int value : sorted) { sum += value; squares += (long) value * value; }
        return ("UVEL_TEST_VECTOR_STATS_RESULT_V1\ninput=" + input.hash() + "\ncount=32\nsum=" + sum
                + "\nsumSquares=" + squares + "\nsorted=" + csv(sorted) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public Verified verify(Grant grant, AssignmentRow task, CompleteRequest request, LocalDateTime now) {
        requireTask(grant, task, now);
        requireRuntime(grant, now);
        if (!KIND.equals(task.modelName()) || !CLIENT.equals(task.clientName()) || !TASK_NAME.equals(task.taskName())) {
            throw new BizException(409, "TEST_COMPUTE_WORKER_TASK_NOT_MARKED");
        }
        if (request == null || !SPEC.equals(request.specVersion()) || !task.completionNonce().equals(request.proofNonce())
                || request.proofTimestamp() == null || request.proofTimestamp() < 0
                || request.proofTimestamp() < clock.millis() - 120_000 || request.proofTimestamp() > clock.millis() + 120_000
                || request.proofTimestamp() >= grant.expiresAt() || request.proofTimestamp() < grant.issuedAt()
                || request.proofTimestamp() >= epoch(task.proofExpiresAt()) || request.proofTimestamp() >= epoch(task.leaseExpiresAt())) {
            throw new BizException(422, "TEST_COMPUTE_RESULT_INVALID");
        }
        Input input = input(grant, task);
        byte[] result;
        try {
            if (request.resultArtifactBase64() == null || request.resultArtifactBase64().length() > 1368) throw new IllegalArgumentException();
            result = Base64.getDecoder().decode(request.resultArtifactBase64());
            if (result.length > 1024 || !Base64.getEncoder().encodeToString(result).equals(request.resultArtifactBase64())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new BizException(422, "TEST_COMPUTE_RESULT_INVALID"); }
        if (!input.hash().equals(request.inputHash()) || !sha256(result).equals(request.resultHash())
                || !MessageDigest.isEqual(expectedResult(input), result)) throw new BizException(422, "TEST_COMPUTE_RESULT_INVALID");
        String domain = "UVEL_TEST_COMPUTE_VERIFIED_V1\nrun=" + grant.runId() + "\nexecutor=" + grant.executorId()
                + "\nowner=" + grant.ownerId() + "\ndevice=" + grant.deviceId() + "\ninstance=" + grant.instanceNo()
                + "\ntask=" + grant.taskNo() + "\ntaskConfig=" + grant.taskConfigId() + "\nnonce=" + task.completionNonce()
                + "\nspec=" + SPEC + "\ninput=" + input.hash() + "\nresult=" + request.resultHash() + "\n";
        return new Verified(sha256(domain.getBytes(StandardCharsets.UTF_8)), input, result, request.resultHash());
    }

    public Map<String, Object> bindings(Grant grant) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionKind", KIND); data.put("specVersion", SPEC); data.put("runId", grant.runId());
        data.put("executorId", grant.executorId()); data.put("ownerId", grant.ownerId()); data.put("deviceId", grant.deviceId());
        data.put("instanceNo", grant.instanceNo()); data.put("taskNo", grant.taskNo()); data.put("taskConfigId", grant.taskConfigId());
        return data;
    }

    public Map<String, Object> proofDetail(Grant grant, Verified verified) {
        Map<String, Object> data = bindings(grant);
        data.put("inputHash", verified.input().hash()); data.put("resultHash", verified.resultHash());
        data.put("inputBytesBase64", Base64.getEncoder().encodeToString(verified.input().bytes()));
        data.put("resultBytesBase64", Base64.getEncoder().encodeToString(verified.result()));
        return data;
    }

    public boolean close(Grant grant, LocalDateTime now) {
        return mapper.closeTestWorkerRuntime(grant.ownerId(), grant.deviceId(), grant.instanceNo(), grant.taskNo(), marker(grant), now) == 1;
    }

    public void record(Grant grant, String action, String bizNo, Map<String, Object> detail) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("COMPUTE_TASK")
                .resourceId(grant.taskNo()).bizNo(bizNo).userId(grant.ownerId()).actorType("TEST_COMPUTE_WORKER")
                .actorUsername(grant.getName()).result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredRuntime() {
        if (!"TEST".equals(property("deployment-scope"))) return;
        Grant grant;
        try { grant = configuredGrant(); } catch (RuntimeException absent) { return; }
        // The non-secret binding intentionally remains usable after enabled=false or credential removal.
        if (!Objects.equals(mapper.lockProductionUser(grant.ownerId()), grant.ownerId())) return;
        var device = mapper.lockOwnedDevice(grant.ownerId(), grant.deviceId());
        if (device == null || !grant.instanceNo().equals(device.instanceNo())) return;
        var runtime = mapper.lockTestWorkerRuntime(grant.ownerId(), grant.deviceId(), grant.instanceNo());
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (runtime != null && (clock.millis() >= grant.expiresAt() || runtime.heartbeatAt() == null
                || runtime.heartbeatAt().isBefore(now.minusSeconds(120))) && close(grant, now)) {
            audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("TEST_COMPUTE_WORKER_RUNTIME_EXPIRED")
                    .resourceType("COMPUTE_TASK").resourceId(grant.taskNo()).bizNo(grant.taskNo()).userId(grant.ownerId())
                    .actorType("SYSTEM").actorUsername("system").result("SUCCESS").riskLevel("MEDIUM")
                    .detail(bindings(grant)).build());
        }
    }

    private long epoch(LocalDateTime time) { return time.atZone(clock.getZone()).toInstant().toEpochMilli(); }
    private String property(String key) { return environment.getProperty(PREFIX + key, ""); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static long positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private static String safe(String value, int max) {
        if (value.length() > max || !value.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException();
        return value;
    }
    private static String csv(int[] values) { return String.join(",", Arrays.stream(values).mapToObj(Integer::toString).toList()); }
    public static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest(bytes)); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
