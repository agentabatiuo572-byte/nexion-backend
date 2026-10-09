package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The three independently observed UNKNOWN writes now assert rejection and zero mutation. */
class IndependentUnknownHistoryRegressionTest {
    final SupportGroupMapper mapper=mock(SupportGroupMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final AdminIdempotencyService keys=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final org.springframework.context.ApplicationEventPublisher events=mock(org.springframework.context.ApplicationEventPublisher.class);
    final SupportGroupService service=new SupportGroupService(mapper,ownership,keys,audit,events);
    final LocalDateTime at=LocalDateTime.of(2026,10,9,0,0);
    final ReadScope all=new ReadScope(1L,ReadMode.ALL,null,null);
    final String reason="independent required reason";
    @BeforeEach void setup(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,
            List.of("service_m1_read","service_m1_write","platform_a1_read","platform_a1_write").stream().map(SimpleGrantedAuthority::new).toList()));
        when(ownership.actorId()).thenReturn(1L);when(ownership.currentSuperAdmin()).thenReturn(true);
        when(ownership.defaultQueryScope(any(),any())).thenReturn(all);
        when(mapper.now()).thenReturn(at);when(mapper.lockAccount(anyLong())).thenAnswer(i->new Account(i.getArgument(0),1,9L));
        when(keys.execute(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
        var group=new Group(8L,"group",1L,"ENABLED",1L);when(mapper.group(8L)).thenReturn(group);when(mapper.lockGroup(8L)).thenReturn(group);
        when(mapper.ownerCurrent(8L)).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Owner(1L,8L,1L,1L,at));
        when(mapper.qualificationCurrent(1L,"SUPERVISOR")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,1L,"SUPERVISOR","ENABLED",1L,at));
        when(mapper.qualification(3L,"SERVICE")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","ENABLED",4L,at));
        when(mapper.touch(8L,1L,at)).thenReturn(1);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void noMutation(){
        verify(mapper,never()).insertMember(any(),any(),any(),any(),any(),any(),any());
        verify(mapper,never()).closeMember(any(),any(),any());
        verify(mapper,never()).insertQualification(any(),any(),any(),any(),any(),any(),any(),any());
        verify(mapper,never()).closeQualification(any(),any(),any());
        verify(mapper,never()).insertRoute(any(),any(),any(),any(),any(),any(),any());
        verify(mapper,never()).closeRoute(any(),any(),any());verifyNoInteractions(audit,events);
        verify(mapper,never()).touch(any(),any(),any());
    }
    private void unknown(Runnable call,String message){assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,e->{assertThat(e.getCode()).isEqualTo(409);assertThat(e.getMessage()).isEqualTo(message);});noMutation();}
    @Test void memberReadUnknownRejectsZeroMutationWithoutResettingVersion(){
        when(mapper.managementAccount(all,3L)).thenReturn(Map.of("name","target","status",1));when(mapper.memberHistoryCount(3L)).thenReturn(1L);
        assertThat(service.memberTarget(3L).currentMember().state()).isEqualTo("UNKNOWN");
        unknown(()->service.move(3L,"unknown-member",new Move(8L,0L,null,1L,reason)),"SUPPORT_GROUP_MEMBER_HISTORY_UNKNOWN");
    }
    @Test void qualificationReadUnknownRejectsZeroMutationWithoutResettingVersion(){
        when(mapper.qualificationAccount(any(),eq(3L))).thenReturn(Map.of("name","target","status",1,"version",9L));
        when(mapper.qualification(3L,"SERVICE")).thenReturn(null);when(mapper.qualificationHistoryCount(3L,"SERVICE")).thenReturn(1L);
        assertThat(service.accountQualifications(3L).qualifications().get(0).observationState()).isEqualTo("UNKNOWN");
        unknown(()->service.qualification(3L,"unknown-qualification",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","DISABLED",0L,9L,reason)),"SUPPORT_QUALIFICATION_HISTORY_UNKNOWN");
    }
    @Test void closedOnlyRouteFactsRejectZeroMutationWithoutResettingVersion(){
        when(mapper.routePoolVersion(20L)).thenReturn(5L);when(mapper.routeHistoryCount(20L)).thenReturn(1L);
        unknown(()->service.route(20L,"unknown-route",new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(8L,0L,null,1L,reason)),"SUPPORT_GROUP_ROUTE_HISTORY_UNKNOWN");
    }
    @Test void firstMembershipWithProvenAbsentHistoryStillStartsAtOne(){
        when(mapper.insertMember(eq(3L),eq(8L),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        when(mapper.member(3L)).thenReturn(new Member(8L,3L,8L,1L,at));
        assertThat(service.move(3L,"first-member",new Move(8L,0L,null,1L,reason)).getCode()).isZero();
        verify(mapper,times(2)).memberHistoryCount(3L);verify(mapper).insertMember(eq(3L),eq(8L),eq(at),eq(1L),eq(1L),anyString(),anyString());
    }
    @Test void firstRouteWithProvenAbsentHistoryStillStartsAtOne(){
        when(mapper.routePoolVersion(20L)).thenReturn(5L);
        var next=new ffdd.opsconsole.content.domain.SupportGroupFacts.Route(4L,20L,8L,1L,at);when(mapper.routeCurrent(20L)).thenReturn(null,next);
        when(mapper.insertRoute(eq(20L),eq(8L),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        assertThat(service.route(20L,"first-route",new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(8L,0L,null,1L,reason)).getCode()).isZero();
        verify(mapper).routeHistoryCount(20L);verify(mapper).insertRoute(eq(20L),eq(8L),eq(at),eq(1L),eq(1L),anyString(),anyString());
    }
    @Test void serviceEnableCannotBootstrapUnknownMembershipBeforeWritingQualification(){
        var removed=new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","REMOVED",4L,at);
        when(mapper.qualification(3L,"SERVICE")).thenReturn(removed);when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.memberHistoryCount(3L)).thenReturn(1L);
        unknown(()->service.qualification(3L,"restore-service",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","ENABLED",4L,9L,reason)),"SUPPORT_GROUP_MEMBER_HISTORY_UNKNOWN");
    }
    @Test void removedQualificationRestoresFromItsVersionAndKeepsExistingUngroupedMember(){
        var removed=new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","REMOVED",4L,at);
        var enabled=new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(4L,3L,"SERVICE","ENABLED",5L,at);
        when(mapper.qualification(3L,"SERVICE")).thenReturn(removed,removed,enabled);when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.memberCurrent(3L)).thenReturn(new Member(8L,3L,null,7L,at));
        when(mapper.closeQualification(3L,4L,at)).thenReturn(1);when(mapper.insertQualification(eq(3L),eq("SERVICE"),eq("ENABLED"),eq(at),eq(5L),eq(1L),anyString(),anyString())).thenReturn(1);
        when(mapper.qualifications(3L)).thenReturn(List.of(enabled));
        assertThat(service.qualification(3L,"restore-removed",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","ENABLED",4L,9L,reason)).getData()).containsExactly(enabled);
        verify(mapper,never()).insertMember(any(),any(),any(),any(),any(),any(),any());verify(mapper,never()).memberHistoryCount(anyLong());
    }
    @Test void removedQualificationCannotRestoreUsingZeroExpectedVersion(){
        when(mapper.qualification(3L,"SERVICE")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","REMOVED",4L,at));
        unknown(()->service.qualification(3L,"restore-stale",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","ENABLED",0L,9L,reason)),"SUPPORT_GROUP_VERSION_CONFLICT");
    }
    @Test void firstServiceQualificationAndMemberRequireBothAbsentHistories(){
        when(mapper.qualification(3L,"SERVICE")).thenReturn(null,null,new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","ENABLED",1L,at));
        when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.insertQualification(eq(3L),eq("SERVICE"),eq("ENABLED"),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        when(mapper.insertMember(eq(3L),isNull(),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        assertThat(service.qualification(3L,"first-service",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","ENABLED",0L,9L,reason)).getCode()).isZero();
        verify(mapper,times(2)).qualificationHistoryCount(3L,"SERVICE");verify(mapper).memberHistoryCount(3L);
    }
    @Test void unsafeCurrentMemberVersionCannotBeWrittenEvenWithMatchingExpected(){
        when(mapper.memberCurrent(3L)).thenReturn(new Member(8L,3L,null,0L,at));
        unknown(()->service.move(3L,"unsafe-member",new Move(8L,0L,null,1L,reason)),"SUPPORT_GROUP_MEMBER_HISTORY_UNKNOWN");
    }
    @Test void qualificationExitCannotIgnoreUnknownMemberHistory(){
        when(mapper.memberHistoryCount(3L)).thenReturn(2L);
        unknown(()->service.qualification(3L,"exit-service",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","DISABLED",4L,9L,reason)),"SUPPORT_GROUP_MEMBER_HISTORY_UNKNOWN");
    }
    @Test void accountRoleChangeCannotIgnoreUnknownQualificationHistoryBeforeCas(){
        when(mapper.qualificationHistoryCount(3L,"SUPERVISOR")).thenReturn(1L);
        unknown(()->service.accountChanging(3L,"finance",false,"role",reason),"SUPPORT_QUALIFICATION_HISTORY_UNKNOWN");
    }
    @Test void accountRoleChangeCannotIgnoreUnknownServiceHistoryBeforeCas(){
        when(mapper.qualification(3L,"SERVICE")).thenReturn(null);when(mapper.qualificationHistoryCount(3L,"SERVICE")).thenReturn(1L);
        unknown(()->service.accountChanging(3L,"finance",false,"role",reason),"SUPPORT_QUALIFICATION_HISTORY_UNKNOWN");
    }
    @Test void accountChangedHookCannotRebuildUnknownServiceQualification(){
        when(mapper.qualification(3L,"SERVICE")).thenReturn(null);when(mapper.qualificationHistoryCount(3L,"SERVICE")).thenReturn(1L);
        unknown(()->service.accountChanged(3L,"finance",false,"role",reason),"SUPPORT_QUALIFICATION_HISTORY_UNKNOWN");
    }
    @Test void safeMaximumMemberVersionCannotWrapOrCreateUnsafeNextVersion(){
        when(mapper.memberCurrent(3L)).thenReturn(new Member(8L,3L,null,9007199254740991L,at));
        unknown(()->service.move(3L,"max-version",new Move(8L,9007199254740991L,null,1L,reason)),"SUPPORT_GROUP_VERSION_CONFLICT");
    }
    @Test void qualificationWithUnsafeCurrentFactCannotBeWritten(){
        when(mapper.qualification(3L,"SERVICE")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(3L,3L,"SERVICE","ENABLED",0L,at));
        unknown(()->service.qualification(3L,"unsafe-qualification",new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","DISABLED",0L,9L,reason)),"SUPPORT_QUALIFICATION_HISTORY_UNKNOWN");
    }
    @Test void uncertainHistoryDiscoveredAfterLocksRejectsAPreviouslyAbsentMember(){
        when(mapper.memberCurrent(3L)).thenReturn(null);when(mapper.memberHistoryCount(3L)).thenReturn(0L,1L);
        unknown(()->service.move(3L,"raced-history",new Move(8L,0L,null,1L,reason)),"SUPPORT_GROUP_MEMBER_HISTORY_UNKNOWN");
        verify(mapper).lockAccount(3L);verify(mapper,times(2)).memberHistoryCount(3L);
    }
}
