package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportAgentMapper;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorScope;
import ffdd.opsconsole.content.mapper.SupportAgentMapper.SupportRoleRow;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class MybatisSupportAgentRepositoryTest {

    @Test
    void assignmentEligibilityQueriesOnlyAuthorizedDirectoryIdsAndRechecksCurrentScope() {
        var mapper = Mockito.mock(SupportAgentMapper.class);
        var scope = new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(6L, ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED, 10L, null);
        var roles = new SupportOperatorScope(null, List.of(2L), List.of(), true, scope);
        when(mapper.listActiveSupportRoleRows()).thenReturn(List.of(new SupportRoleRow(2L, "SUPPORT")));
        when(mapper.listAssignmentEligibleAgentIds(List.of(7L, 8L), roles)).thenReturn(List.of(7L));
        var repository = new MybatisSupportAgentRepository(mapper);
        assertThat(repository.listAssignmentEligibleAgentIds(List.of(), scope)).isEmpty();
        Mockito.verifyNoInteractions(mapper);
        assertThat(repository.listAssignmentEligibleAgentIds(List.of(7L, 8L), scope)).containsExactly(7L);
        verify(mapper).listActiveSupportRoleRows();
        verify(mapper).listAssignmentEligibleAgentIds(List.of(7L, 8L), roles);
        Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void candidateSqlPreservesDirectoryButRequiresAccountAndUniqueServiceAuthority() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(SupportAgentMapper.class);
        var statement = configuration.getMappedStatement(SupportAgentMapper.class.getName() + ".listAssignmentEligibleAgentIds");
        for (var mode : ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.values()) {
            var read = new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(6L, mode, null, null);
            var scope = new SupportOperatorScope(null, List.of(2L), List.of(), false, read);
            var sql = statement.getBoundSql(java.util.Map.of("scope", scope, "directoryIds", List.of(7L))).getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("scope_agent.id IN", "a.id=scope_agent.id", "a.status=1", "p.enabled=1",
                    "scope_q.qualification_kind='SERVICE'", "scope_q.state='ENABLED'", "scope_q.ends_at IS NULL",
                    "scope_q_other.starts_at <= UTC_TIMESTAMP(6)", "scope_q_other.ends_at>UTC_TIMESTAMP(6)")
                    .doesNotContain("p.busy", "p.transferable", "${", "&lt;");
            if (mode == ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED) {
                assertThat(sql).contains("scope_group.supervisor_admin_id=", "qualification_kind='SUPERVISOR'");
            }
            assertThat(statement.getBoundSql(java.util.Map.of("scope", scope, "directoryIds", List.of())).getSql()).contains("1=0");
        }
    }

    @Test
    void lightweightPageAndCountKeepTheSameActorScopeWithoutAccountOrSchemaFanout() {
        SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
        List<SupportOperatorRecord> rows = List.of(new SupportOperatorRecord(6L, "Self", "self@example.test", "avatar", 9L));
        SupportOperatorScope self = new SupportOperatorScope(6L, List.of(2L), List.of(), false);
        SupportOperatorScope supervisor = new SupportOperatorScope(null, List.of(2L), List.of(), false);
        when(mapper.countSupportOperators(self)).thenReturn(1L);
        when(mapper.pageSupportOperators(self, 5L, 0L)).thenReturn(rows);
        when(mapper.countSupportOperators(supervisor)).thenReturn(4L);
        when(mapper.pageSupportOperators(supervisor, 2L, 2L)).thenReturn(rows);
        MybatisSupportAgentRepository repository = new MybatisSupportAgentRepository(mapper);

        assertThat(repository.countSupportOperators(self)).isEqualTo(1L);
        assertThat(repository.pageSupportOperators(self, 5L, 0L)).containsExactlyElementsOf(rows);
        assertThat(repository.countSupportOperators(supervisor)).isEqualTo(4L);
        assertThat(repository.pageSupportOperators(supervisor, 2L, 2L)).containsExactlyElementsOf(rows);

        verify(mapper).countSupportOperators(self);
        verify(mapper).pageSupportOperators(self, 5L, 0L);
        verify(mapper).countSupportOperators(supervisor);
        verify(mapper).pageSupportOperators(supervisor, 2L, 2L);
        Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void normalizedSupportRoleAndUnusablePrimaryIdsMatchTheRealA1Resolver() {
        List<SupportRoleRow> roles = List.of(new SupportRoleRow(1L, "SUPER_ADMIN"),
                new SupportRoleRow(2L, "SUPPORT_"), new SupportRoleRow(3L, "SUPPORT"),
                new SupportRoleRow(4L, "SUPPORT_ADMIN"), new SupportRoleRow(5L, null),
                new SupportRoleRow(6L, " \t"), new SupportRoleRow(7L, "_"),
                new SupportRoleRow(8L, "CONFIG-ADMIN"), new SupportRoleRow(9L, "SUP-PORT"),
                new SupportRoleRow(10L, "FINANCE"), new SupportRoleRow(11L, "RISK"),
                new SupportRoleRow(12L, "CONTENT"), new SupportRoleRow(13L, "GROWTH"),
                new SupportRoleRow(14L, "AUDITOR"), new SupportRoleRow(15L, " \u00a0SUPPORT\u00a0 "),
                new SupportRoleRow(16L, "\u017fUPPORT"), new SupportRoleRow(17L, "\u2003"));
        SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
        when(mapper.listActiveSupportRoleRows()).thenReturn(roles);
        var scope = new MybatisSupportAgentRepository(mapper).supportOperatorScope(6L);
        AdminRoleRelationMapper relations = Mockito.mock(AdminRoleRelationMapper.class);
        OpsAdminAccountService a1 = a1RoleResolver(roles, relations);

        assertThat(scope.supportRoleIds()).containsExactly(2L, 3L, 15L, 16L);
        assertThat(scope.superFallbackToSupport()).isFalse();
        assertThat(scope.unusablePrimaryRoleIds()).containsExactly(5L);
        for (SupportRoleRow role : roles) {
            when(relations.activeRoleCode(6L)).thenReturn(role.roleCode());
            Optional<String> resolved = ReflectionTestUtils.invokeMethod(a1, "roleFromRelation", 6L);
            assertThat(scope.supportRoleIds().contains(role.id())).isEqualTo(resolved.filter("support"::equals).isPresent());
            assertThat(scope.unusablePrimaryRoleIds().contains(role.id())).isEqualTo(resolved.isEmpty());
        }
        verify(mapper).listActiveSupportRoleRows();
        Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingPrimarySuperFallbackMatchesTheRealA1FirstRoleAndNegativeControls() {
        List<List<SupportRoleRow>> dictionaries = List.of(
                List.of(new SupportRoleRow(2L, "SUPPORT_"), new SupportRoleRow(3L, "FINANCE")),
                List.of(new SupportRoleRow(1L, "FINANCE"), new SupportRoleRow(2L, "SUPPORT_")),
                List.of(new SupportRoleRow(2L, "SUPPORT_"), new SupportRoleRow(9L, "SUPER_ADMIN")),
                List.of(new SupportRoleRow(2L, "SUPPORT_"), new SupportRoleRow(9L, "SUPER_")),
                List.of());
        for (List<SupportRoleRow> roles : dictionaries) {
            SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
            when(mapper.listActiveSupportRoleRows()).thenReturn(roles);
            var scope = new MybatisSupportAgentRepository(mapper).supportOperatorScope(null);
            OpsAdminAccountService a1 = a1RoleResolver(roles, Mockito.mock(AdminRoleRelationMapper.class));
            AdminEntity target = new AdminEntity();
            target.setSuperAdmin(1);
            String superRole = ReflectionTestUtils.invokeMethod(a1, "defaultRole", target);
            assertThat(scope.superFallbackToSupport()).isEqualTo("support".equals(superRole));
            assertThat(scope.superFallbackToSupport()).isEqualTo(roles.equals(dictionaries.get(0)));
            target.setSuperAdmin(0);
            assertThat((String) ReflectionTestUtils.invokeMethod(a1, "defaultRole", target)).isEqualTo("unassigned");
        }
    }

    private OpsAdminAccountService a1RoleResolver(List<SupportRoleRow> roles, AdminRoleRelationMapper relations) {
        OpsOptionsMapper roleMapper = Mockito.mock(OpsOptionsMapper.class);
        when(roleMapper.selectList(Mockito.any())).thenReturn(roles.stream().map(row -> {
            AdminRoleOptionEntity role = new AdminRoleOptionEntity();
            role.setId(row.id());
            role.setRoleCode(row.roleCode());
            role.setStatus(1);
            role.setIsDeleted(0);
            return role;
        }).toList());
        OpsAdminAccountService a1 = Mockito.mock(OpsAdminAccountService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(a1, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(a1, "roleRelationMapper", relations);
        return a1;
    }

    @Test
    void beanInitializesSchemaBeforeFirstReadWithoutDdlInLaterCommandTransactions() {
        SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
        when(mapper.countSeatTypeColumn()).thenReturn(1L);
        when(mapper.countProfileVersionColumn()).thenReturn(1L);
        when(mapper.countActiveUserColumn()).thenReturn(1L);
        when(mapper.countActiveUserUniqueIndex()).thenReturn(1L);
        when(mapper.listProfiles(List.of(2L))).thenReturn(List.of());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(SupportAgentMapper.class, () -> mapper);
            context.registerBean(MybatisSupportAgentRepository.class);
            context.refresh();
            var repository = context.getBean(MybatisSupportAgentRepository.class);
            verify(mapper).createProfileTable();
            verify(mapper).createAssignmentTable();
            Mockito.clearInvocations(mapper);
            repository.listProfiles(List.of(2L));
            repository.ensureSchema();
            verify(mapper).listProfiles(List.of(2L));
            Mockito.verifyNoMoreInteractions(mapper);
        }
    }

    @Test
    void concurrentRequestsInitializeStructureOnceAndNeverRewriteLegacyEvidence() throws Exception {
        SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
        when(mapper.countSeatTypeColumn()).thenReturn(1L);
        when(mapper.countAssignmentTypeColumn()).thenReturn(0L);
        when(mapper.countActiveUserColumn()).thenReturn(1L);
        when(mapper.countActiveUserUniqueIndex()).thenReturn(1L);
        MybatisSupportAgentRepository repository = new MybatisSupportAgentRepository(mapper);

        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    repository.ensureSchema();
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        verify(mapper, times(1)).createProfileTable();
        verify(mapper, Mockito.never()).backfillSeatType();
        verify(mapper, Mockito.never()).dropAssignmentTypeColumn();
        verify(mapper, times(1)).createAssignmentTable();
        verify(mapper, times(1)).countDuplicateActiveCustomers();
    }
}
