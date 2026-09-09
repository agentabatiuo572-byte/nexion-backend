package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.SupportAgentProfileRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Opt-in proof of the production service's @Transactional proxy. The unproxied
 * control intentionally leaves the first probe write; the Spring proxy rolls it back.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class M1SupportAvailabilitySpringTransactionMySqlIntegrationTest {
    @Test
    void secondCasConflictRollsBackFirstAvailabilityWriteOnlyThroughSpringTransactionalProxy() throws Exception {
        try (var fixture = M1SupportAvailabilityMySqlFixture.openFromEnvironment()) {
            fixture.jdbc().execute("CREATE TABLE m1_availability_tx_probe (admin_id BIGINT PRIMARY KEY)");
            OpsSupportAgentService target = target(fixture);

            assertThatThrownBy(() -> updateBoth(target))
                    .hasMessageContaining("SUPPORT_AGENT_PROFILE_VERSION_CONFLICT");
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM m1_availability_tx_probe", Integer.class))
                    .as("unproxied control proves this regression requires the service transaction interceptor")
                    .isEqualTo(1);
            fixture.jdbc().update("DELETE FROM m1_availability_tx_probe");

            try (var context = springContext(target, fixture.jdbc().getDataSource())) {
                OpsSupportAgentService proxied = context.getBean(OpsSupportAgentService.class);
                assertThat(AopUtils.isAopProxy(proxied)).isTrue();
                assertThatThrownBy(() -> updateBoth(proxied))
                        .hasMessageContaining("SUPPORT_AGENT_PROFILE_VERSION_CONFLICT");
            }
            assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM m1_availability_tx_probe", Integer.class))
                    .isZero();
        }
    }

    private static AnnotationConfigApplicationContext springContext(OpsSupportAgentService target, DataSource dataSource) {
        var context = new AnnotationConfigApplicationContext();
        context.register(SpringTransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(OpsSupportAgentService.class, () -> target);
        context.refresh();
        return context;
    }

    private static void updateBoth(OpsSupportAgentService service) {
        service.updateAvailabilityForLoad(Map.of(
                "2", new SupportAgentLoadStateRequest(0, true, 1L),
                "5", new SupportAgentLoadStateRequest(0, true, 1L)));
    }

    private static OpsSupportAgentService target(M1SupportAvailabilityMySqlFixture fixture) {
        SupportAgentRepository repository = mock(SupportAgentRepository.class);
        OpsAdminAccountService accounts = mock(OpsAdminAccountService.class);
        when(accounts.currentOperator()).thenReturn(Optional.of(operator("1", "Root", "superadmin")));
        when(accounts.overview()).thenReturn(ApiResult.ok(overview(List.of(
                operator("2", "Support A", "support"), operator("5", "Support B", "support")))));
        when(repository.findProfile(2L)).thenReturn(Optional.of(profile(2L)));
        when(repository.findProfile(5L)).thenReturn(Optional.of(profile(5L)));
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id == 2L) {
                fixture.jdbc().update("INSERT INTO m1_availability_tx_probe(admin_id) VALUES (?)", id);
                return true;
            }
            return false;
        }).when(repository).updateProfileCas(anyLong(), anyString(), anyString(), any(), any(), anyInt(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyLong(), any());
        return new OpsSupportAgentService(repository, accounts, mock(AuditLogService.class),
                mock(AdminIdempotencyService.class), OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SupportAgentProfileRecord profile(long id) {
        return new SupportAgentProfileRecord(id, "GENERAL", "通用客服", List.of("support"), List.of("keep"),
                12, true, true, false, 1L, "2026-09-10T00:00:00");
    }

    private static AdminAccountOverview overview(List<AdminAccountOverview.OperatorRecord> operators) {
        return new AdminAccountOverview(new AdminAccountOverview.AdminAccountStats(operators.size(), 1, 0, 0, 1, 0),
                List.of(), operators, List.of(), List.of());
    }

    private static AdminAccountOverview.OperatorRecord operator(String id, String name, String role) {
        return new AdminAccountOverview.OperatorRecord(id, name, name.toLowerCase(), name.toLowerCase() + "@nexion.io",
                role, true, "enabled", "", 0, "", "MAIL_DISPATCHED");
    }

    @Configuration
    @EnableTransactionManagement
    static class SpringTransactionConfiguration { }
}