package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

class A2ProposalRecoveryTest {
    final AuditLogService audit = mock(AuditLogService.class);
    final AuditOperationTicketMapper tickets = mock(AuditOperationTicketMapper.class);
    final AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
    final ffdd.opsconsole.shared.security.AdminPermissionCache permissions = mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
    final A2AccessPolicy access = new A2AccessPolicy(roles, tickets, permissions);
    final OpsAuditCenterService service = new OpsAuditCenterService(null, audit, null, tickets,
            null, null, null, null, null, access, new com.fasterxml.jackson.databind.ObjectMapper(), null);

    @BeforeEach void login() {
        var auth = new UsernamePasswordAuthenticationToken("9", null, List.of(new SimpleGrantedAuthority("platform_a2_read")));
        auth.setDetails(Map.of("subjectType", "ADMIN", "username", "maker"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(roles.resolveCode()).thenReturn("GROWTH");
        when(roles.isActiveAdministrator(9L)).thenReturn(true);
        when(permissions.getPermissionCodes(9L)).thenReturn(java.util.Set.of("platform_a2_read", "device_e6_read"));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void returnsCurrentTicketByExactMakerKeyWithoutOverviewOrWrites() throws Exception {
        var ticket = ticket("E", "approved");
        when(audit.findA2ProposalId(9, "original-key")).thenReturn("WO-RECOVERY");
        when(tickets.selectActiveByOperationId("WO-RECOVERY")).thenReturn(ticket);
        var result = service.proposalByIdempotencyKey("original-key");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("WO-RECOVERY");
        assertThat(result.getData().status()).isEqualTo("approved");
        verify(audit).findA2ProposalId(9, "original-key");
        verify(tickets).selectActiveByOperationId("WO-RECOVERY");
        verifyNoMoreInteractions(audit, tickets);
        assertThat(OpsAuditCenterService.class.getMethod("proposalByIdempotencyKey", String.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test void absentOrAnotherMakersKeyIsUnknownWithoutTicketLookup() {
        assertThat(service.proposalByIdempotencyKey("another-maker-key").getCode()).isEqualTo(404);
        verify(audit).findA2ProposalId(9, "another-maker-key");
        verifyNoInteractions(tickets);
    }

    @Test void sameMakerCannotRecoverAfterBusinessDomainScopeNarrows() {
        when(audit.findA2ProposalId(9, "key")).thenReturn("WO-RECOVERY");
        when(tickets.selectActiveByOperationId("WO-RECOVERY")).thenReturn(ticket("D", "pending"));
        var result = service.proposalByIdempotencyKey("key");
        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getData()).isNull();
    }

    @Test void disabledAdministratorCannotEvenLookUpOriginalKey() {
        when(roles.isActiveAdministrator(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.proposalByIdempotencyKey("key"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        verifyNoInteractions(audit, tickets);
    }

    @Test void revokedDbPermissionWinsOverOldTokenAuthority() {
        when(permissions.getPermissionCodes(9L)).thenReturn(java.util.Set.of("device_e6_read"));
        assertThatThrownBy(() -> service.proposalByIdempotencyKey("key"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        verifyNoInteractions(audit, tickets);
    }

    @Test void customRoleUsesCurrentDbBusinessDomainsNotOldTokenAuthorities() {
        when(roles.resolveCode()).thenReturn("CUSTOM");
        when(audit.findA2ProposalId(9,"key")).thenReturn("WO-RECOVERY");
        when(tickets.selectActiveByOperationId("WO-RECOVERY")).thenReturn(ticket("E","pending"));
        assertThat(service.proposalByIdempotencyKey("key").getCode()).isZero();
        when(permissions.getPermissionCodes(9L)).thenReturn(java.util.Set.of("platform_a2_read","finance_d1_read"));
        assertThat(service.proposalByIdempotencyKey("key").getCode()).isEqualTo(404);
    }

    private AuditOperationTicketEntity ticket(String domain, String status) {
        var row = new AuditOperationTicketEntity();
        row.setOperationId("WO-RECOVERY"); row.setSourceDomain(domain); row.setOperatorName("maker");
        row.setStatus(status); row.setAction("phone policy"); row.setObjectText("phone");
        row.setBeforeValue("old"); row.setAfterValue("new"); row.setOperatorRole("GROWTH");
        row.setOperationType("param"); row.setRoleGate("approver"); row.setReason("original reason");
        return row;
    }
}
