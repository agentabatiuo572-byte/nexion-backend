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
        var service = new AppAmbassadorApplicationService(mapper, new MockEnvironment());

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
        var result = new AppAmbassadorApplicationService(mapper, new MockEnvironment()).submit(
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
        when(mapper.findByKey(7L, "SANDBOX", "run-1", "amb-key")).thenAnswer(ignored -> inserted.get() == null
                ? null : row(inserted.get()));
        when(mapper.insertApplication(any())).thenAnswer(invocation -> { inserted.set(invocation.getArgument(0)); return 1; });
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-1");
        environment.setActiveProfiles("local-sandbox");

        var result = new AppAmbassadorApplicationService(mapper, environment).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX").containsEntry("runId", "run-1");
        assertThat(inserted.get().sourceEnvironment()).isEqualTo("SANDBOX");
    }

    @Test
    void differentPayloadCannotReuseTheSameKey() {
        var mapper = mock(AppAmbassadorApplicationMapper.class);
        when(mapper.lockUser(7L)).thenReturn(new AppAmbassadorApplicationMapper.UserScope(0, "V5", "Alice", "Tokyo"));
        when(mapper.findByKey(7L, "PRODUCTION", "", "amb-key")).thenReturn(new AppAmbassadorApplicationMapper.ApplicationRow(
                1L, 7L, "amb-key", "different", "PENDING", "Tokyo", LocalDate.now().plusDays(7),
                new BigDecimal("3000"), "venue", LocalDateTime.now(), "PRODUCTION", ""));
        var result = new AppAmbassadorApplicationService(mapper, new MockEnvironment()).submit(
                7L, LocalDate.now().plusDays(7), "Tokyo", new BigDecimal("3000"), "venue", "amb-key");
        assertThat(result.getCode()).isEqualTo(409);
        verify(mapper, never()).insertApplication(any());
    }

    private AppAmbassadorApplicationMapper.ApplicationRow row(AppAmbassadorApplicationMapper.ApplicationWrite write) {
        return new AppAmbassadorApplicationMapper.ApplicationRow(1L, write.userId(), write.idempotencyKey(), write.requestHash(),
                "PENDING", write.city(), write.eventDate(), write.budget(), write.bucket(), LocalDateTime.now(),
                write.sourceEnvironment(), write.runId());
    }
}
