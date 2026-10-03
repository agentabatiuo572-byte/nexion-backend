package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.device.application.TestComputeWorkerService.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestComputeWorkerService.*;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.*;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TestComputeWorkerServiceTest {
    static final Instant INSTANT = Instant.parse("2026-10-02T12:00:00Z");
    static final LocalDateTime NOW = LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC);
    static final String NONCE = "a".repeat(64);
    static final String TOKEN = "tw1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    final AppTaskAssignmentMapper mapper = mock(AppTaskAssignmentMapper.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final MockEnvironment env = environment("CTA-TEST-ONE");
    final TestComputeWorkerService service = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), env);
    Grant grant;

    @BeforeEach void setup() {
        grant = service.authenticate("Bearer " + TOKEN);
        when(mapper.lockTestWorkerRuntime(7L, 11L, "NEX-TEST-INSTANCE")).thenReturn(new TestWorkerRuntimeRow(
                "ONLINE", grant.taskNo(), CLIENT, service.marker(grant), NOW, null));
    }

    static MockEnvironment environment(String task) {
        var env = new MockEnvironment();
        Map<String, String> values = Map.ofEntries(Map.entry("enabled", "true"), Map.entry("deployment-scope", "TEST"),
                Map.entry("executor-id", "executor-one"), Map.entry("credential-sha256", TestComputeWorkerService.sha256(new byte[32])),
                Map.entry("owner-id", "7"), Map.entry("device-id", "11"), Map.entry("instance-no", "NEX-TEST-INSTANCE"),
                Map.entry("task-no", task), Map.entry("task-config-id", "TASK-EM"), Map.entry("run-id", "TEST355-UNIT"),
                Map.entry("issued-at", String.valueOf(INSTANT.toEpochMilli() - 60_000)),
                Map.entry("expires-at", String.valueOf(INSTANT.toEpochMilli() + 600_000)));
        values.forEach((key, value) -> env.setProperty("nexion.compute-task.test-worker." + key, value));
        return env;
    }

    static AssignmentRow task(String taskNo) {
        return new AssignmentRow(taskNo, 11L, "TASK-EM", TASK_NAME, "EM", KIND, CLIENT, "RUNNING",
                new BigDecimal("0.25"), 5, 0, NOW.minusSeconds(10), NOW.plusHours(24), null, null, NONCE, NOW.plusHours(24));
    }

    @Test void actualJavaComputationMatchesIndependentlyFrozenPythonGoldenBytes() {
        Input input = service.input(grant, task(grant.taskNo()));
        assertThat(input.hash()).isEqualTo("493e2c5266ee50db44b186bc5c38ca3b8439fe3280e6f0a377215ec9b0fe299b");
        byte[] expected = service.expectedResult(input);
        assertThat(new String(expected, StandardCharsets.UTF_8)).contains("sum=-1184\nsumSquares=10529756\n").endsWith("\n");
        assertThat(TestComputeWorkerService.sha256(expected)).isEqualTo("08848de76d730b47d5cde8e594d6f58e0a6eae32dd3200ffa4206a5c8df621cc");
        var verified = service.verify(grant, task(grant.taskNo()), validRequest(), NOW);
        assertThat(verified.result()).containsExactly(expected);
        assertThat(verified.proofHash()).hasSize(64).isNotEqualTo(input.hash());
    }

    CompleteRequest validRequest() {
        Input input = service.input(grant, task(grant.taskNo()));
        byte[] result = service.expectedResult(input);
        return new CompleteRequest(SPEC, input.hash(), TestComputeWorkerService.sha256(result),
                Base64.getEncoder().encodeToString(result), NONCE, INSTANT.toEpochMilli());
    }

    static MockEnvironment continuousEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("server.forward-headers-strategy", "none");
        Map<String, String> values = Map.ofEntries(Map.entry("deployment-scope", "TEST"),
                Map.entry("continuous.enabled", "true"), Map.entry("continuous.executor-id", "test355-continuous"),
                Map.entry("continuous.credential-sha256", sha256(new byte[32])),
                Map.entry("continuous.owner-id", Long.toString(CONTINUOUS_OWNER)),
                Map.entry("continuous.device-id", Long.toString(CONTINUOUS_DEVICE)),
                Map.entry("continuous.instance-no", CONTINUOUS_INSTANCE), Map.entry("continuous.task-config-id", CONTINUOUS_CONFIG));
        values.forEach((key, value) -> environment.setProperty("nexion.compute-task.test-worker." + key, value));
        return environment;
    }

    @Test void continuousDefaultOffAndSeparateCredentialNeverExtendFiniteAuthority() {
        assertThatThrownBy(() -> service.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4)))
                .hasMessage("TEST_COMPUTE_CONTINUOUS_DISABLED");
        MockEnvironment continuous = continuousEnvironment();
        var worker = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), continuous);
        assertThat(worker.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4)).getName()).isEqualTo("test-worker-continuous:test355-continuous");
        assertThatThrownBy(() -> worker.authenticateContinuous("Bearer " + TOKEN)).hasMessage("TEST_COMPUTE_WORKER_AUTH_INVALID");
        assertThatThrownBy(() -> worker.authenticate("Bearer tc1_" + TOKEN.substring(4))).hasMessage("TEST_COMPUTE_WORKER_DISABLED");
        verifyNoInteractions(mapper, audit);
    }

    @Test void continuousRequiresActualDevTestFixedBindingAndUnmodifiedSocketAddressesBeforeSql() {
        MockEnvironment continuous = continuousEnvironment();
        var worker = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), continuous);
        for (String[] profiles : List.of(new String[]{"prod"}, new String[]{"dev", "prod"}, new String[0])) {
            continuous.setActiveProfiles(profiles);
            assertThatThrownBy(() -> worker.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4)))
                    .hasMessage("TEST_COMPUTE_CONTINUOUS_DISABLED");
        }
        continuous.setActiveProfiles("dev");
        continuous.setProperty("server.forward-headers-strategy", "native");
        assertThatThrownBy(worker::requireContinuousEnabled).hasMessage("TEST_COMPUTE_CONTINUOUS_DISABLED");
        continuous.setProperty("server.forward-headers-strategy", "none");
        continuous.setProperty("nexion.compute-task.test-worker.continuous.device-id", "1153");
        assertThatThrownBy(() -> worker.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4)))
                .hasMessage("TEST_COMPUTE_WORKER_AUTH_INVALID");
        verifyNoInteractions(mapper, audit);
    }

    @Test void continuousUsesActualBusinessLeaseAndStableTaskScopeWithoutInventingFifteenMinuteExpiry() {
        MockEnvironment continuous = continuousEnvironment();
        var worker = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), continuous);
        var identity = worker.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4));
        Grant job = worker.continuousGrant(identity, "CTA-CONTINUOUS", INSTANT.minusSeconds(3600).toEpochMilli(), INSTANT.plusSeconds(3600).toEpochMilli());
        worker.requireCurrent(job);
        assertThat(worker.marker(job)).startsWith("test355-v2:");
        assertThat(worker.scope(job, "COMPLETE")).isEqualTo(worker.scope(worker.continuousGrant(identity,
                job.taskNo(), job.issuedAt() - 1000, job.expiresAt() + 1000), "COMPLETE"));
        Grant expired = worker.continuousGrant(identity, job.taskNo(), job.issuedAt(), INSTANT.toEpochMilli());
        assertThatThrownBy(() -> worker.requireCurrent(expired)).hasMessage("TEST_COMPUTE_WORKER_AUTH_INVALID");
        continuous.setProperty("nexion.compute-task.test-worker.continuous.enabled", "false");
        assertThatThrownBy(() -> worker.requireCurrent(job)).hasMessage("TEST_COMPUTE_CONTINUOUS_DISABLED");
    }

    @Test void continuousTaskCannotUseForeignDeviceConfigOrAgentAndKeepsTestRewardLimit() {
        MockEnvironment continuous = continuousEnvironment();
        var worker = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), continuous);
        var identity = worker.authenticateContinuous("Bearer tc1_" + TOKEN.substring(4));
        Grant job = worker.continuousGrant(identity, "CTA-CONTINUOUS", INSTANT.minusSeconds(60).toEpochMilli(), INSTANT.plusSeconds(3600).toEpochMilli());
        var row = new AssignmentRow(job.taskNo(), CONTINUOUS_DEVICE, CONTINUOUS_CONFIG, TASK_NAME, "EM", KIND, CLIENT,
                "RUNNING", new BigDecimal("0.045006"), 5, 0, NOW.minusSeconds(60), NOW.plusHours(1), null, null, NONCE, NOW.plusHours(1));
        assertThatThrownBy(() -> worker.requireTask(job, row, NOW)).hasMessage("TEST_COMPUTE_WORKER_REWARD_LIMIT");
        when(mapper.lockAssignment(CONTINUOUS_OWNER, job.taskNo(), "PRODUCTION")).thenReturn(row);
        when(mapper.lockTestWorkerRuntime(CONTINUOUS_OWNER, CONTINUOUS_DEVICE, CONTINUOUS_INSTANCE)).thenReturn(
                new TestWorkerRuntimeRow("ONLINE", job.taskNo(), CLIENT, "foreign-agent", NOW, null));
        assertThatThrownBy(() -> worker.continuousTaskGrant(identity, job.taskNo())).hasMessage("TEST_COMPUTE_WORKER_RUNTIME_STALE");
    }

    @ParameterizedTest @ValueSource(strings={"sum", "sort", "crlf", "extra", "bom", "hash", "nonce", "version", "input", "base64", "oversize"})
    void correctClientDigestDoesNotAuthorizeWrongSemanticsOrNoncanonicalArtifact(String change) {
        CompleteRequest valid = validRequest();
        String result = new String(Base64.getDecoder().decode(valid.resultArtifactBase64()), StandardCharsets.UTF_8);
        result = switch (change) {
            case "sum" -> result.replace("sum=-1184", "sum=-1183");
            case "sort" -> result.replace("sorted=", "sorted=+0,");
            case "crlf" -> result.replace("\n", "\r\n");
            case "extra" -> result + "\n";
            case "bom" -> "\ufeff" + result;
            case "oversize" -> "x".repeat(1025);
            default -> result;
        };
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        var bad = new CompleteRequest(change.equals("version") ? "PRODUCTION" : SPEC,
                change.equals("input") ? "b".repeat(64) : valid.inputHash(),
                change.equals("hash") ? "b".repeat(64) : TestComputeWorkerService.sha256(bytes),
                change.equals("base64") ? valid.resultArtifactBase64().replace("=", "") : Base64.getEncoder().encodeToString(bytes),
                change.equals("nonce") ? "b".repeat(64) : NONCE, valid.proofTimestamp());
        if (change.equals("base64") && bad.resultArtifactBase64().equals(valid.resultArtifactBase64())) {
            bad = new CompleteRequest(SPEC, valid.inputHash(), valid.resultHash(), valid.resultArtifactBase64() + "=", NONCE, valid.proofTimestamp());
        }
        final CompleteRequest rejected = bad;
        assertThatThrownBy(() -> service.verify(grant, task(grant.taskNo()), rejected, NOW)).hasMessage("TEST_COMPUTE_RESULT_INVALID");
        verifyNoInteractions(audit);
    }

    @ParameterizedTest @ValueSource(longs={-1, Long.MIN_VALUE, Long.MAX_VALUE, 1790942279999L, 1790942520001L})
    void timestampExtremesCannotOverflowIntoValidity(long timestamp) {
        var valid = validRequest();
        var bad = new CompleteRequest(SPEC, valid.inputHash(), valid.resultHash(), valid.resultArtifactBase64(), NONCE, timestamp);
        assertThatThrownBy(() -> service.verify(grant, task(grant.taskNo()), bad, NOW)).hasMessage("TEST_COMPUTE_RESULT_INVALID");
    }

    @ParameterizedTest @ValueSource(strings={"", "DISABLED", "PRODUCTION", "SANDBOX", "test"})
    void deploymentScopeIsExactAndAlwaysBeforeSql(String scope) {
        env.setProperty("nexion.compute-task.test-worker.deployment-scope", scope);
        assertThatThrownBy(() -> service.authenticate("Bearer " + TOKEN)).hasMessage("TEST_COMPUTE_WORKER_DISABLED");
        verifyNoInteractions(mapper, audit);
    }

    @Test void changedBindingExpiredGrantAndDisableAreRecheckedAfterAuthentication() {
        env.setProperty("nexion.compute-task.test-worker.owner-id", "8");
        assertThatThrownBy(() -> service.requireCurrent(grant)).hasMessage("TEST_COMPUTE_WORKER_AUTH_INVALID");
        env.setProperty("nexion.compute-task.test-worker.owner-id", "7");
        env.setProperty("nexion.compute-task.test-worker.expires-at", String.valueOf(INSTANT.toEpochMilli()));
        assertThatThrownBy(() -> service.requireCurrent(grant)).hasMessage("TEST_COMPUTE_WORKER_AUTH_INVALID");
        env.setProperty("nexion.compute-task.test-worker.enabled", "false");
        assertThatThrownBy(() -> service.requireCurrent(grant)).hasMessage("TEST_COMPUTE_WORKER_DISABLED");
    }

    @Test void deterministicClaimKeyAndScopeRemainStableAcrossRestartAndPreventDifferentKeys() {
        String key = "TEST355-CLAIM-" + TestComputeWorkerService.sha256(grant.taskNo().getBytes(StandardCharsets.UTF_8));
        service.requireFixedKey(grant, "CLAIM", key);
        assertThatThrownBy(() -> service.requireFixedKey(grant, "CLAIM", key + "x")).hasMessage("TEST_COMPUTE_WORKER_IDEMPOTENCY_INVALID");
        assertThat(service.scope(grant, "COMPLETE")).hasSizeLessThanOrEqualTo(96);
        var restarted = new TestComputeWorkerService(mapper, audit, Clock.fixed(INSTANT, ZoneOffset.UTC), env);
        assertThat(restarted.scope(grant, "CLAIM")).isEqualTo(service.scope(grant, "CLAIM"));
        assertThat(service.newTaskNo(grant)).matches("CTA-TEST-[A-F0-9]{32}");
    }

    @Test void staleOrTakenOverRuntimeCannotCompleteOrBeReplaced() {
        when(mapper.lockTestWorkerRuntime(7L, 11L, "NEX-TEST-INSTANCE")).thenReturn(new TestWorkerRuntimeRow(
                "ONLINE", grant.taskNo(), CLIENT, "other-agent", NOW, null));
        assertThatThrownBy(() -> service.verify(grant, task(grant.taskNo()), validRequest(), NOW)).hasMessage("TEST_COMPUTE_WORKER_RUNTIME_STALE");
        assertThatThrownBy(() -> service.markOnline(grant, NOW)).hasMessage("TEST_COMPUTE_WORKER_RUNTIME_CONFLICT");
        verify(mapper, never()).markTestWorkerOnline(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void secondPrecisionHeartbeatDoesNotBecomeFutureOnInsertOrUpdate(boolean missingRuntime) {
        var runtime = new AtomicReference<TestWorkerRuntimeRow>(missingRuntime ? null : new TestWorkerRuntimeRow(
                "OFFLINE", null, CLIENT, "", NOW.minusHours(1), null));
        when(mapper.lockTestWorkerRuntime(7L, 11L, "NEX-TEST-INSTANCE")).thenAnswer(i -> runtime.get());
        org.mockito.stubbing.Answer<Integer> persist = i -> {
            LocalDateTime written = i.getArgument(5);
            runtime.set(new TestWorkerRuntimeRow("ONLINE", grant.taskNo(), CLIENT, service.marker(grant),
                    written.plusNanos(500_000_000).withNano(0), null));
            return 1;
        };
        if (missingRuntime) when(mapper.insertTestWorkerRuntime(any(), any(), any(), any(), any(), any())).thenAnswer(persist);
        else when(mapper.markTestWorkerOnline(any(), any(), any(), any(), any(), any())).thenAnswer(persist);

        service.markOnline(grant, NOW.plusNanos(750_000_000));
        assertThat(runtime.get().heartbeatAt()).isEqualTo(NOW);
    }

    @ParameterizedTest @ValueSource(longs={-121, -120, 0, 1})
    void existingHeartbeatStillRejectsFutureAndExpiredValues(long secondsFromNow) {
        when(mapper.lockTestWorkerRuntime(7L, 11L, "NEX-TEST-INSTANCE")).thenReturn(new TestWorkerRuntimeRow(
                "ONLINE", grant.taskNo(), CLIENT, service.marker(grant), NOW.plusSeconds(secondsFromNow), null));
        if (secondsFromNow < -120 || secondsFromNow > 0) {
            assertThatThrownBy(() -> service.requireRuntime(grant, NOW)).hasMessage("TEST_COMPUTE_WORKER_RUNTIME_STALE");
        } else {
            service.requireRuntime(grant, NOW);
        }
        verify(mapper, never()).insertTestWorkerRuntime(any(), any(), any(), any(), any(), any());
        verify(mapper, never()).markTestWorkerOnline(any(), any(), any(), any(), any(), any());
    }

    @Test void actualAuditSerializationPreservesSmallArtifactsAndExplicitSafeTestActorWithoutSecretOrUserImpersonation() throws Exception {
        AuditLogMapper auditMapper = mock(AuditLogMapper.class);
        var policy = mock(A2RuntimePolicy.class); when(policy.schemaVersion()).thenReturn("v1");
        var actualAudit = new AuditLogService(auditMapper, new AuditLogSanitizer(new ObjectMapper()),
                new ApplicationNameProperties(), new AuditProperties(), mock(AdminMapper.class), policy);
        var actual = new TestComputeWorkerService(mapper, actualAudit, Clock.fixed(INSTANT, ZoneOffset.UTC), env);
        var verified = actual.verify(grant, task(grant.taskNo()), validRequest(), NOW);
        var detail = actual.proofDetail(grant, verified);
        detail.put("proofHash", verified.proofHash());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(grant, null, List.of()));
        try {
            actual.record(grant, "TASK_ASSIGNMENT_COMPLETED", "CTR-TEST-ONE", detail);
            var captor = org.mockito.ArgumentCaptor.forClass(AuditLogMapper.AuditLogWrite.class);
            verify(auditMapper).insertAuditLog(captor.capture());
            var row = captor.getValue();
            assertThat(row.actorType()).isEqualTo("TEST_COMPUTE_WORKER");
            assertThat(row.actorUsername()).isEqualTo("test-worker:executor-one");
            String json = row.detailJson();
            assertThat(json).hasSizeLessThan(4096).doesNotContain("serialization", "truncated", TOKEN,
                    env.getProperty("nexion.compute-task.test-worker.credential-sha256"), "admin:", "\"actorType\":\"USER\"");
            var parsed = new ObjectMapper().readTree(json);
            assertThat(Base64.getDecoder().decode(parsed.get("inputBytesBase64").textValue())).containsExactly(verified.input().bytes());
            assertThat(Base64.getDecoder().decode(parsed.get("resultBytesBase64").textValue())).containsExactly(verified.result());
            assertThat(parsed.get("proofHash").textValue()).isEqualTo(verified.proofHash());
        } finally { SecurityContextHolder.clearContext(); }
    }
}
