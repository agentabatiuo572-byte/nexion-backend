package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class A2AccessPolicyTest {
    private final AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
    private final AuditOperationTicketMapper ticketMapper = mock(AuditOperationTicketMapper.class);
    private final A2AccessPolicy policy = new A2AccessPolicy(roles, ticketMapper);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void riskIsServerScopedToAccountAndRiskDomains() {
        when(roles.resolveCode()).thenReturn("RISK");
        AuditLogQueryRequest query = policy.constrain(new AuditLogQueryRequest());
        assertThat(query.getAllowedDomains()).containsExactly("C", "K");
        assertThat(policy.current().canSee("D", "risk.user")).isFalse();
    }

    @Test
    void supportIsForcedToAuthenticatedActorEvenWhenRequestSpoofsOperator() {
        when(roles.resolveCode()).thenReturn("SUPPORT");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("9", null, List.of());
        auth.setDetails(Map.of("username", "support.user"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setOperator("victim.user");
        AuditLogQueryRequest constrained = policy.constrain(query);
        assertThat(constrained.getOperator()).isNull();
        assertThat(constrained.getOperatorExact()).isEqualTo("support.user");
    }

    @Test
    void auditorHasUnrestrictedRead() {
        when(roles.resolveCode()).thenReturn("AUDITOR");
        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).isNull();
        assertThat(policy.hasUnrestrictedRead()).isTrue();
    }

    @Test
    void everyA2RoleUsesTheDocumentedServerSideScopeAndUnknownRolesSeeNothing() {
        Map<String, List<String>> scoped = Map.of(
                "FINANCE", List.of("D"),
                "RISK", List.of("C", "K"),
                "GROWTH", List.of("E", "H"),
                "CONTENT", List.of("I"));
        for (Map.Entry<String, List<String>> entry : scoped.entrySet()) {
            when(roles.resolveCode()).thenReturn(entry.getKey());
            assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains())
                    .as(entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
        }

        for (String unrestricted : List.of("SUPER_ADMIN", "AUDITOR")) {
            when(roles.resolveCode()).thenReturn(unrestricted);
            assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains())
                    .as(unrestricted)
                    .isNull();
        }

        when(roles.resolveCode()).thenReturn(null);
        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).containsExactly("__NONE__");
        when(roles.resolveCode()).thenReturn("UNRECOGNIZED");
        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).containsExactly("__NONE__");
    }

    @Test
    void customRoleVisibilityScopeIncludesReadOrWriteBusinessAuthoritiesButNotA2SurfacePermissions() {
        when(roles.resolveCode()).thenReturn("ACC_CHECKER_114336");
        authenticate("platform_a2_read", "platform_a2_operation_approve", "network_f3_read",
                "device_e6_read", "platform_a6_read");

        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).containsExactly("A", "E", "F");
        assertThat(policy.current().canSee("F", "maker")).isTrue();
        assertThat(policy.current().canSee("A", "maker")).isTrue();

        authenticate("platform_a2_read", "platform_a2_operation_approve", "network_f1_write",
                "device_e6_write", "platform_a6_read");

        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).containsExactly("A", "E", "F");
        assertThat(policy.current().canSee("F", "maker")).isTrue();
        assertThat(policy.current().canSee("E", "maker")).isTrue();
        assertThat(policy.current().canSee("A", "maker")).isTrue();

        authenticate("platform_a2_read", "platform_a2_operation_approve", "platform_a6_write");
        assertThat(policy.constrain(new AuditLogQueryRequest()).getAllowedDomains()).containsExactly("A");
    }

    @Test
    void operationDecisionUsesTheSameRowScopeAndUnknownIdsFailClosed() {
        when(roles.resolveCode()).thenReturn("ACC_CHECKER_114336");
        authenticate("platform_a2_read", "platform_a2_operation_approve", "network_f3_read");
        AuditOperationTicketEntity visible = ticket("F3", "maker.f");
        AuditOperationTicketEntity crossDomain = ticket("E6", "maker.e");
        when(ticketMapper.selectActiveByOperationId("WO-F-1")).thenReturn(visible);
        when(ticketMapper.selectActiveByOperationId("WO-E-1")).thenReturn(crossDomain);

        assertThat(policy.canAccessOperation("wo-f-1")).isTrue();
        assertThat(policy.canAccessOperation("WO-E-1")).isFalse();
        assertThat(policy.canAccessOperation("WO-MISSING")).isFalse();
        assertThat(policy.canAccessOperation(" ")).isFalse();
        assertThat(policy.canAccessOperation("../../WO-F-1")).isFalse();
        assertThat(policy.canAccessOperation("WO-" + "X".repeat(62))).isFalse();

        verify(ticketMapper).selectActiveByOperationId("WO-F-1");
        verify(ticketMapper).selectActiveByOperationId("WO-E-1");
        verify(ticketMapper).selectActiveByOperationId("WO-MISSING");
        verify(ticketMapper, never()).selectActiveByOperationId(" ");
        verify(ticketMapper, never()).selectActiveByOperationId("../../WO-F-1");

        when(roles.resolveCode()).thenReturn("FINANCE");
        assertThat(policy.canAccessOperation("WO-E-1")).isFalse();
    }

    @Test
    void clientCannotInjectServerOwnedScopeAndInvalidDomainFailsClosed() {
        when(roles.resolveCode()).thenReturn("SUPER_ADMIN");
        AuditLogQueryRequest injected = new AuditLogQueryRequest();
        injected.setAllowedDomains(List.of());
        assertThat(policy.constrain(injected).getAllowedDomains()).isNull();

        AuditLogQueryRequest invalid = new AuditLogQueryRequest();
        invalid.setDomain("[A-M");
        invalid.setAllowedDomains(List.of("A", "D"));
        AuditLogQueryRequest constrained = policy.constrain(invalid);
        assertThat(constrained.getDomain()).isNull();
        assertThat(constrained.getAllowedDomains()).containsExactly("__NONE__");
    }

    private void authenticate(String... authorities) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "114336",
                "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        authentication.setDetails(Map.of("username", "checker.f"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private AuditOperationTicketEntity ticket(String sourceDomain, String operator) {
        AuditOperationTicketEntity ticket = new AuditOperationTicketEntity();
        ticket.setSourceDomain(sourceDomain);
        ticket.setOperatorName(operator);
        return ticket;
    }
}
