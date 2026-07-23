package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class A2AccessPolicyTest {
    private final AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
    private final A2AccessPolicy policy = new A2AccessPolicy(roles);

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
}
