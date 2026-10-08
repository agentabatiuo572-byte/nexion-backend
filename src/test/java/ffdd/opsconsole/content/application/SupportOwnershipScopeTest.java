package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupportOwnershipScopeTest {
    private final SupportBindingMapper bindings=mock(SupportBindingMapper.class);
    private final SupportGroupMapper groups=mock(SupportGroupMapper.class);
    private final SupportOwnershipService service=new SupportOwnershipService(bindings,groups);
    private final LocalDateTime at=LocalDateTime.of(2026,10,8,0,0);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("11","",List.of()));
    }
    private Qualification qualification(String kind) { return new Qualification(1L,11L,kind,"ENABLED",1L,at); }

    @Test void scopeRejectsInvalidIdentityAndPersonalGroupOrOtherAgent() {
        assertThatThrownBy(()->new ReadScope(null,ReadMode.ALL,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReadScope(11L,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReadScope(11L,ReadMode.PERSONAL,8L,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReadScope(11L,ReadMode.PERSONAL,null,12L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReadScope(11L,ReadMode.ALL,null,0L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void currentSupportReaderRejectsEmptyRolesRatherThanFallingThroughToAnotherDomain() {
        login();assertThatThrownBy(service::currentSupportReader).hasMessage("SUPPORT_ACCOUNT_UNAVAILABLE");
        when(bindings.roles(11L)).thenReturn(List.of("SUPPORT"));assertThat(service.currentSupportReader()).isTrue();
        when(bindings.roles(11L)).thenReturn(List.of("SUPPORT","SUPER_ADMIN"));assertThat(service.currentSupportReader()).isFalse();
        when(bindings.roles(11L)).thenReturn(List.of("FINANCE"));assertThat(service.currentSupportReader()).isFalse();
    }

    @Test void superAdminProbeUsesOnlyExactCurrentRolesWithoutQualificationFallback() {
        login();
        for(String role:List.of("SUPER","SUPERADMIN","SUPER_ADMIN","super","superadmin","super_admin")) {
            when(bindings.roles(11L)).thenReturn(List.of(role));assertThat(service.currentSuperAdmin()).isTrue();
        }
        for(List<String> roles:List.of(List.<String>of(),List.of("SUPPORT","SUPERVISOR"),List.of("FINANCE","SUPER_ADMINISTRATOR"," SUPER_ADMIN"))) {
            when(bindings.roles(11L)).thenReturn(roles);assertThat(service.currentSuperAdmin()).isFalse();
        }
        verify(bindings,never()).rolesSnapshot(11L);verifyNoInteractions(groups);
    }

    @Test void superAdminProbeRechecksRevokedRoleAndHardAuthorizationStillRejects() {
        login();when(bindings.roles(11L)).thenReturn(List.of("SUPER_ADMIN"),List.of("SUPPORT"));
        assertThat(service.currentSuperAdmin()).isTrue();assertThat(service.currentSuperAdmin()).isFalse();
        assertThatThrownBy(service::requireSuperAdmin).hasMessage("SUPPORT_RULES_FORBIDDEN");
        verify(bindings,times(3)).roles(11L);verify(bindings,never()).rolesSnapshot(11L);
    }

    @Test void superAdminProbeDoesNotSwallowCurrentRoleReadFailure() {
        login();when(bindings.roles(11L)).thenThrow(new IllegalStateException("ROLE_READ_FAILED"));
        assertThatThrownBy(service::currentSuperAdmin).hasMessage("ROLE_READ_FAILED");
    }

    @Test void noGroupSupervisorKeepsEmptyManagedModeWithoutPersonalOrAllFallback() {
        login();when(groups.qualificationCurrent(11L,"SUPERVISOR")).thenReturn(qualification("SUPERVISOR"));
        assertThat(service.defaultQueryScope(null,null)).isEqualTo(new ReadScope(11L,ReadMode.MANAGED,null,null));
        verify(groups,never()).qualificationCurrent(11L,"SERVICE");
        assertThatThrownBy(()->service.queryScope(ReadMode.ALL,null,null)).hasMessage("SUPPORT_SCOPE_FORBIDDEN");
    }

    @Test void personalAndManagedModesRemainSeparateForDualQualifiedAccount() {
        login();when(groups.qualificationCurrent(11L,"SUPERVISOR")).thenReturn(qualification("SUPERVISOR"));
        when(groups.qualificationCurrent(11L,"SERVICE")).thenReturn(qualification("SERVICE"));
        when(bindings.eligibleAgent(11L)).thenReturn(1);
        assertThat(service.queryScope(ReadMode.PERSONAL,null,null).mode()).isEqualTo(ReadMode.PERSONAL);
        assertThat(service.defaultQueryScope(null,null).mode()).isEqualTo(ReadMode.MANAGED);
        assertThatThrownBy(()->service.queryScope(ReadMode.PERSONAL,8L,null)).hasMessage("SUPPORT_READ_SCOPE_INVALID");
    }

    @Test void allModeRequiresCurrentDatabaseRoleAndRechecksAfterRoleRevocation() {
        login();when(bindings.roles(11L)).thenReturn(List.of("SUPER_ADMIN"),List.of());
        assertThat(service.queryScope(ReadMode.ALL,null,null).mode()).isEqualTo(ReadMode.ALL);
        assertThatThrownBy(()->service.queryScope(ReadMode.ALL,null,null)).hasMessage("SUPPORT_SCOPE_FORBIDDEN");
    }

    @Test void forgedGroupAndAgentFiltersRejectInsteadOfDroppingNarrowingCondition() {
        login();when(groups.qualificationCurrent(11L,"SUPERVISOR")).thenReturn(qualification("SUPERVISOR"));
        assertThatThrownBy(()->service.queryScope(ReadMode.MANAGED,8L,null)).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        assertThatThrownBy(()->service.queryScope(ReadMode.MANAGED,null,12L)).hasMessage("SUPPORT_AGENT_NOT_FOUND");
    }

    @Test void personalReadCannotAuthorizeManagingCustomer() {
        login();
        assertThatThrownBy(()->service.requireManagingCustomer(20L)).hasMessage("SUPPORT_SCOPE_FORBIDDEN");
        verify(bindings,never()).readableCustomer(new ReadScope(11L,ReadMode.PERSONAL,null,null),20L);
    }

    @Test void managementDirectoryDoesNotRequireTargetServiceAvailability() {
        when(groups.readableAgent(new ReadScope(11L,ReadMode.MANAGED,null,null),12L)).thenReturn(1);
        assertThat(service.canReadAgent(11L,12L)).isTrue();
        verify(bindings,never()).eligibleAgent(12L);
        verify(groups,never()).qualificationCurrent(12L,"SERVICE");
    }

    @Test void supervisorCanReadOwnAvatarWithoutServiceQualification() {
        when(groups.qualificationCurrent(11L,"SUPERVISOR")).thenReturn(qualification("SUPERVISOR"));
        assertThat(service.canReadAgent(11L,11L)).isTrue();
    }

    @Test void allocationNullGroupIsNotWildcardEvenForAll() {
        login();when(bindings.roles(11L)).thenReturn(List.of("SUPER_ADMIN"));
        when(groups.readableAgent(new ReadScope(11L,ReadMode.ALL,null,12L),12L)).thenReturn(1);
        when(groups.memberCurrent(12L)).thenReturn(new Member(2L,12L,8L,1L,at));
        assertThatThrownBy(()->service.requireTargetMember(12L,null)).hasMessage("SUPPORT_AGENT_UNAVAILABLE");
        verify(bindings,never()).eligibleAgent(12L);
    }

    @Test void allocationRequiresProvenMemberAndStillUsableServiceProfile() {
        login();when(bindings.roles(11L)).thenReturn(List.of("SUPER_ADMIN"));
        when(groups.readableAgent(new ReadScope(11L,ReadMode.ALL,null,12L),12L)).thenReturn(1);
        when(groups.memberCurrent(12L)).thenReturn(new Member(2L,12L,null,1L,at));
        when(groups.qualificationCurrent(12L,"SERVICE")).thenReturn(new Qualification(3L,12L,"SERVICE","ENABLED",1L,at));
        assertThatThrownBy(()->service.requireTargetMember(12L,null)).hasMessage("SUPPORT_AGENT_UNAVAILABLE");
        when(bindings.eligibleAgent(12L)).thenReturn(1);
        assertThatCode(()->service.requireTargetMember(12L,null)).doesNotThrowAnyException();
    }

    @Test void ticketUsesImmutableCustomerAndCurrentPersonalUnionWithoutLegacySupervisor() {
        login();when(bindings.ticketCustomer("ticket-1")).thenReturn(20L);
        when(bindings.readableCustomer(new ReadScope(11L,ReadMode.PERSONAL,null,null),20L)).thenReturn(1);
        assertThatCode(()->service.readTicket("ticket-1")).doesNotThrowAnyException();
        verify(bindings,never()).roles(11L);
        verify(bindings,never()).currentAgent(20L);
    }

    @Test void customerObjectSelectsPersonalForDualAccountsGroupOutsideCustomer() {
        login();when(bindings.readableCustomer(new ReadScope(11L,ReadMode.PERSONAL,null,null),20L)).thenReturn(1);
        assertThat(service.customerQueryScope(20L)).isEqualTo(new ReadScope(11L,ReadMode.PERSONAL,null,null));
        verify(bindings).readableCustomer(new ReadScope(11L,ReadMode.MANAGED,null,null),20L);
        verifyNoInteractions(groups);
    }

    @Test void customerObjectSelectsManagedWhenActuallyInOwnedGroupAndRejectsRevokedObject() {
        login();when(bindings.readableCustomer(new ReadScope(11L,ReadMode.MANAGED,null,null),20L)).thenReturn(1,0);
        assertThat(service.customerQueryScope(20L).mode()).isEqualTo(ReadMode.MANAGED);
        assertThatThrownBy(()->service.customerQueryScope(20L)).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
    }

    @Test void invalidationEventCopiesAdminSetAndCannotBecomeMutableAuthorization() {
        var ids=new HashSet<>(Set.of(11L));var event=new ScopeChanged(ids,"OWNER_CHANGED");
        ids.add(12L);assertThat(event.affectedAdminIds()).containsExactly(11L);
        assertThatThrownBy(()->event.affectedAdminIds().add(13L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
