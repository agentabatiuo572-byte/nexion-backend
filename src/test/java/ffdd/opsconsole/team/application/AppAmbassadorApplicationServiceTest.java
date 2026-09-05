package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppAmbassadorApplicationMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppAmbassadorApplicationServiceTest {
    @Test
    void createsAndReplaysOneSelfScopedApplication() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        var inserted = new AtomicReference<AppAmbassadorApplicationMapper.ApplicationWrite>();
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V5", "Alice", "Tokyo"));
        when(mapper.findByKey(7L, "PRODUCTION", "", "amb-key")).thenAnswer(ignored -> inserted.get() == null
                ? null : row(inserted.get()));
        when(mapper.insertApplication(any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        var service = service(mapper, new MockEnvironment());

        var first = service.submit(7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");
        var replay = service.submit(7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(first.getData()).containsEntry("status", "PENDING").containsEntry("sourceEnvironment", "PRODUCTION");
        verify(mapper).insertApplication(any());
    }

    @Test
    void rejectsIneligibleRankBeforeInsert() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V4", "Alice", "Tokyo"));
        var result = service(mapper, new MockEnvironment()).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("AMBASSADOR_V5_REQUIRED");
        verify(mapper, never()).insertApplication(any());
    }

    @Test
    void sandboxApplicationUsesServerRunAndSandboxIdentity() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        var inserted = new AtomicReference<AppAmbassadorApplicationMapper.ApplicationWrite>();
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(1, "V6", "Alice", "Tokyo"));
        when(mapper.findByKey(7L, "SANDBOX", "sandbox-run-20260816", "amb-key")).thenAnswer(ignored -> inserted.get() == null
                ? null : row(inserted.get()));
        when(mapper.insertApplication(any())).thenAnswer(invocation -> { inserted.set(invocation.getArgument(0)); return 1; });
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "sandbox-run-20260816");
        environment.setActiveProfiles("test");

        var result = service(mapper, environment).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX").containsEntry("runId", "sandbox-run-20260816");
        assertThat(inserted.get().sourceEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndProductionProvenance() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        var inserted = new AtomicReference<AppAmbassadorApplicationMapper.ApplicationWrite>();
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V6", "Alice", "Tokyo"));
        when(mapper.findByKey(7L, "PRODUCTION", "", "amb-key")).thenAnswer(ignored -> inserted.get() == null
                ? null : row(inserted.get()));
        when(mapper.insertApplication(any())).thenAnswer(invocation -> { inserted.set(invocation.getArgument(0)); return 1; });
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = service(mapper, environment).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        assertThat(inserted.get().sourceEnvironment()).isEqualTo("PRODUCTION");
    }

    @Test
    void differentPayloadCannotReuseTheSameKey() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V5", "Alice", "Tokyo"));
        when(mapper.findByKey(7L, "PRODUCTION", "", "amb-key")).thenReturn(new AppAmbassadorApplicationMapper.ApplicationRow(
                1L, 7L, "amb-key", "different", "PENDING", "Tokyo", LocalDate.now().plusDays(7),
                new BigDecimal("3000"), "venue", LocalDateTime.now(), "PRODUCTION", ""));
        var result = service(mapper, new MockEnvironment()).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");
        assertThat(result.getCode()).isEqualTo(409);
        verify(mapper, never()).insertApplication(any());
    }

    @Test
    void historyReturnsTheRequestedServerPageAndStableMetadata() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        when(mapper.user(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V5", "Alice", "Tokyo"));
        when(mapper.count(7L, "PRODUCTION", "")).thenReturn(3L);
        var historyRow = new AppAmbassadorApplicationMapper.ApplicationRow(
                2L, 7L, "amb-old", "hash", "APPROVED", "Osaka", LocalDate.now().plusDays(30),
                new BigDecimal("1000"), "travel", LocalDateTime.now().minusDays(1), "PRODUCTION", "");
        when(mapper.list(7L, "PRODUCTION", "", 1L, 1)).thenReturn(List.of(historyRow));

        var result = service(mapper, new MockEnvironment()).history(7L, 2, 1);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("pageNum", 2).containsEntry("pageSize", 1)
                .containsEntry("total", 3L).containsEntry("hasMore", true)
                .containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat(result.getData().get("rows").toString()).contains("APPROVED", "Osaka");
        verify(mapper).list(7L, "PRODUCTION", "", 1L, 1);
    }

    private AppAmbassadorApplicationService service(AppAmbassadorApplicationMapper mapper, MockEnvironment environment) {
        var policy = mock(AppAmbassadorPolicyService.class);
        var snapshot = new AppAmbassadorPolicyService.PolicySnapshot(
                "test-policy", 1L, new BigDecimal("3000"), java.util.List.of(), "PRODUCTION", "");
        when(policy.requiredPolicy(7L)).thenReturn(snapshot);
        when(policy.budgetAllowed(any(), any(), any())).thenReturn(true);
        return new AppAmbassadorApplicationService(mapper, environment, policy);
    }
    private AppAmbassadorApplicationMapper.ApplicationRow row(AppAmbassadorApplicationMapper.ApplicationWrite write) {
        return new AppAmbassadorApplicationMapper.ApplicationRow(1L, write.userId(), write.idempotencyKey(), write.requestHash(),
                "PENDING", write.city(), write.eventDate(), write.budget(), write.bucket(), LocalDateTime.now(),
                write.sourceEnvironment(), write.runId());
    }
}
