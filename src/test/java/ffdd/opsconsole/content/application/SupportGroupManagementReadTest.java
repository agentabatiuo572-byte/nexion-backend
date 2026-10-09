package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupportGroupManagementReadTest {
    final SupportGroupMapper mapper=mock(SupportGroupMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportGroupService service=new SupportGroupService(mapper,ownership,mock(AdminIdempotencyService.class),
            mock(AuditLogService.class),mock(org.springframework.context.ApplicationEventPublisher.class));
    final LocalDateTime at=LocalDateTime.of(2026,10,9,0,0);
    final ReadScope all=new ReadScope(7L,ReadMode.ALL,null,13L);
    @BeforeEach void setup(){
        login("service_m1_read","platform_a1_read");when(ownership.actorId()).thenReturn(7L);
        when(ownership.defaultQueryScope(null,13L)).thenReturn(all);
        when(mapper.managementAccount(all,13L)).thenReturn(Map.of("id",13L,"name","客服甲","status",0,"version",9L));
        when(mapper.qualificationAccount(new ReadScope(7L,ReadMode.ALL,null,null),13L))
                .thenReturn(Map.of("id",13L,"name","客服甲","status",0,"version",9L));
    }
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    private void login(String... permissions){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7",null,
            Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));}
    @Test void ungroupedMemberPreservesNonzeroHistoryVersionAndDisabledAccount(){
        when(mapper.memberCurrent(13L)).thenReturn(new Member(81L,13L,null,4L,at));
        when(mapper.boundCount(13L)).thenReturn(3L);
        var view=service.memberTarget(13L);
        assertThat(view.adminId()).isEqualTo("13");assertThat(view.name()).isEqualTo("客服甲");assertThat(view.accountStatus()).isZero();
        assertThat(view.currentMember().state()).isEqualTo("AVAILABLE");assertThat(view.currentMember().id()).isEqualTo("81");
        assertThat(view.currentMember().groupId()).isNull();assertThat(view.currentMember().version()).isEqualTo(4L);assertThat(view.boundCustomers()).isEqualTo(3L);
        verify(mapper,never()).memberHistoryCount(anyLong());
    }
    @Test void absentDoesNotBecomeVersionZeroAndIncompleteHistoryIsUnknown(){
        assertThat(service.memberTarget(13L).currentMember()).isEqualTo(new ffdd.opsconsole.content.dto.SupportGroupManagementViews.MemberFact("ABSENT",null,null,null));
        when(mapper.memberHistoryCount(13L)).thenReturn(2L);
        assertThat(service.memberTarget(13L).currentMember()).isEqualTo(new ffdd.opsconsole.content.dto.SupportGroupManagementViews.MemberFact("UNKNOWN",null,null,null));
    }
    @Test void unsafeMemberFactIsUnknownInsteadOfTruncatedOrVersionZero(){
        for(Member member:List.of(new Member(81L,13L,null,9007199254740992L,at),new Member(0L,13L,null,2L,at),new Member(81L,13L,0L,2L,at))) {
            when(mapper.memberCurrent(13L)).thenReturn(member);
            assertThat(service.memberTarget(13L).currentMember().state()).isEqualTo("UNKNOWN");
            assertThat(service.memberTarget(13L).currentMember().version()).isNull();
        }
    }
    @Test void latestReadReturnsFreshMemberVersionWithoutOldCache(){
        when(mapper.memberCurrent(13L)).thenReturn(new Member(81L,13L,8L,4L,at),new Member(82L,13L,null,5L,at));
        assertThat(service.memberTarget(13L).currentMember().version()).isEqualTo(4L);
        assertThat(service.memberTarget(13L).currentMember().version()).isEqualTo(5L);
    }
    @Test void revokedOrCrossGroupMemberNeverReadsHistoryOrCounts(){
        doThrow(new BizException(404,"SUPPORT_AGENT_NOT_FOUND")).when(ownership).defaultQueryScope(null,13L);
        assertThatThrownBy(()->service.memberTarget(13L)).hasMessage("SUPPORT_AGENT_NOT_FOUND");
        verify(mapper,never()).memberCurrent(anyLong());verify(mapper,never()).boundCount(anyLong());
    }
    @Test void authorizationDriftAtScopedAccountQueryStillDenies(){
        when(mapper.managementAccount(all,13L)).thenReturn(null);
        assertThatThrownBy(()->service.memberTarget(13L)).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        verify(mapper,never()).memberCurrent(anyLong());
    }
    @Test void personalReaderCannotReadManagementMemberProjection(){
        when(ownership.defaultQueryScope(null,13L)).thenReturn(new ReadScope(13L,ReadMode.PERSONAL,null,13L));
        assertThatThrownBy(()->service.memberTarget(13L)).hasMessage("SUPPORT_GROUP_FORBIDDEN");
        verify(mapper,never()).memberCurrent(anyLong());
    }
    @Test void removedQualificationAndAbsentSecondKindPreserveIndependentVersions(){
        when(mapper.qualificationForRead(13L,"SERVICE")).thenReturn(new Qualification(33L,13L,"SERVICE","REMOVED",6L,at));
        var view=service.accountQualifications(13L);
        assertThat(view.accountVersion()).isEqualTo("9");assertThat(view.qualifications()).hasSize(2);
        assertThat(view.qualifications().get(0)).isEqualTo(new ffdd.opsconsole.content.dto.SupportGroupManagementViews.QualificationFact("SERVICE","REMOVED","AVAILABLE","33",6L));
        assertThat(view.qualifications().get(1)).isEqualTo(new ffdd.opsconsole.content.dto.SupportGroupManagementViews.QualificationFact("SUPERVISOR",null,"ABSENT",null,null));
        verify(ownership).requireSuperAdmin();
    }
    @Test void missingCurrentQualificationWithHistoryIsUnknownAndUnsafeVersionIsUnknown(){
        when(mapper.qualificationHistoryCount(13L,"SERVICE")).thenReturn(1L);
        when(mapper.qualificationForRead(13L,"SUPERVISOR")).thenReturn(new Qualification(33L,13L,"SUPERVISOR","DISABLED",9007199254740992L,at));
        var facts=service.accountQualifications(13L).qualifications();
        assertThat(facts).allSatisfy(q->{assertThat(q.observationState()).isEqualTo("UNKNOWN");assertThat(q.version()).isNull();assertThat(q.state()).isNull();});
    }
    @Test void unsafeAccountVersionDeniesInsteadOfGuessing(){
        when(mapper.qualificationAccount(any(),eq(13L))).thenReturn(Map.of("id",13L,"name","客服甲","status",1,"version",9007199254740992L));
        assertThatThrownBy(()->service.accountQualifications(13L)).hasMessage("SUPPORT_ACCOUNT_VERSION_UNKNOWN");
        verify(mapper,never()).qualificationForRead(anyLong(),anyString());
    }
    @Test void m1ReadDoesNotGrantQualificationAccessAndRevokedSuperCannotRead(){
        login("service_m1_read");
        assertThatThrownBy(()->service.accountQualifications(13L)).hasMessage("SUPPORT_GROUP_FORBIDDEN");
        login("platform_a1_read");doThrow(new BizException(403,"SUPER_ADMIN_REQUIRED")).when(ownership).requireSuperAdmin();
        assertThatThrownBy(()->service.accountQualifications(13L)).hasMessage("SUPER_ADMIN_REQUIRED");
        verify(mapper,never()).qualificationForRead(anyLong(),anyString());
    }
    @Test void invalidTargetIdsAreRejectedBeforeScopeAndAccountRead(){
        for(Long id:Arrays.asList(null,0L,-1L,9007199254740992L)) {
            assertThatThrownBy(()->service.memberTarget(id)).hasMessage("SUPPORT_GROUP_REQUEST_INVALID");
            assertThatThrownBy(()->service.accountQualifications(id)).hasMessage("SUPPORT_GROUP_REQUEST_INVALID");
        }
        verify(ownership,never()).defaultQueryScope(any(),any());
    }
    @Test void detailUsesSameRealBlockingCountersAsStatusAndGivesOnlyCurrentGroupSteps(){
        var scope=new ReadScope(7L,ReadMode.MANAGED,8L,null);when(ownership.defaultQueryScope(8L,null)).thenReturn(scope);
        when(mapper.readableGroup(scope,8L)).thenReturn(new Group(8L,"甲组",7L,"ENABLED",3L));
        when(mapper.memberCount(8L)).thenReturn(2L);when(mapper.routeCount(8L)).thenReturn(3L);when(mapper.pendingGroupOperations(8L)).thenReturn(4L);
        var view=service.detail(8L);assertThat(view).containsEntry("pendingOperations",4L);
        assertThat(((Map<?,?>)view.get("blockers")).get("canExit")).isEqualTo(false);
        assertThat(((Map<?,?>)view.get("blockers")).get("pendingOperations")).isEqualTo(4L);
        assertThat(view.get("blockers").toString()).contains("groupId=8","未结束").doesNotContain("groupId=9");
    }
}
