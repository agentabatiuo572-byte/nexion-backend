package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportRandom.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SupportRandomOriginalAgentTest {
    final SupportBindingMapper mapper=mock(SupportBindingMapper.class);
    final SupportGroupMapper groups=mock(SupportGroupMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final ProductionSupportPathGuard production=mock(ProductionSupportPathGuard.class);
    final SupportBindingService service=new SupportBindingService(mapper,ownership,mock(AdminIdempotencyService.class),mock(AuditLogService.class),
            mock(ApplicationEventPublisher.class),mock(SupportAgentRepository.class),production,mock(SupportTicketOwnerService.class),groups);
    final SupportRules rules=new SupportRules(1L,30,15,7,"LIMITED",0,"AUTO_RANDOM",null);
    final ReadScope scope=new ReadScope(99L,ReadMode.ALL,null,null);
    final AtomicReference<SupportAssignment> assigned=new AtomicReference<>();
    Candidate candidate(long id){return new Candidate(id,1L,1L,id,1L,id,1L);}
    void setup(List<Candidate> candidates,Map<String,Object> pool){
        var route=new Route(1L,30L,1L,1L,null);var group=new Group(1L,"test",99L,"ENABLED",1L);
        when(mapper.rules()).thenReturn(rules);when(mapper.poolVersion(30L)).thenReturn(1L);
        when(mapper.poolAttempt(30L)).thenReturn(pool);when(mapper.current(30L)).thenAnswer(c->assigned.get());
        when(groups.routeSnapshot(30L)).thenReturn(route);when(groups.routeCurrent(30L)).thenReturn(route);
        when(groups.group(1L)).thenReturn(group);when(groups.lockGroup(1L)).thenReturn(group);when(groups.readableGroup(scope,1L)).thenReturn(group);
        when(groups.ownerCurrent(1L)).thenReturn(new Owner(1L,1L,99L,1L,null));
        when(groups.qualificationCurrent(99L,"SUPERVISOR")).thenReturn(new Qualification(1L,99L,"SUPERVISOR","ENABLED",1L,null));
        when(groups.candidates(1L)).thenReturn(candidates);when(groups.candidateIdsSnapshot(1L)).thenReturn(candidates.stream().map(Candidate::agentId).toList());
        when(groups.lockAccount(anyLong())).thenAnswer(c->new Account(c.getArgument(0),1,1L));
        when(ownership.defaultQueryScope(null,null)).thenReturn(scope);when(mapper.eligibleAgent(anyLong())).thenReturn(1);
        when(groups.closeRoute(eq(1L),eq(1L),any())).thenReturn(1);
        when(mapper.insertAssignment(anyLong(),eq(30L),anyString(),anyString(),eq("RANDOM"),eq(30L),eq(0),isNull(),eq(1L),anyString()))
            .thenAnswer(c->{assigned.set(new SupportAssignment(900L,30L,c.getArgument(0),1L,"RANDOM",30L,0,null,1L));return 1;});
    }
    Map<String,Object> excluded(){return Map.of("reason","DEPTH_LIMIT","excludedAgentAdminId",11L);}
    @Test void onlyOriginalAgentLeavesNewCustomerWaitingInsteadOfRedrawingSameAgent(){
        setup(List.of(candidate(11)),excluded());
        assertThat(service.randomUnbound(30L,rules,"register:30","registration","New customer",null)).isNull();
        verify(mapper,never()).insertAssignment(any(),any(),any(),any(),any(),any(),anyInt(),any(),any(),any());
        verify(mapper).attemptPool(30L,"WAITING_CANDIDATE","NO_CANDIDATE");
    }
    @Test void previewAndConfirmUseTheSameFilteredCandidatesAndRejectCandidateDrift(){
        setup(List.of(candidate(11),candidate(12)),excluded());
        var preview=service.freezeRandomScope(new Customer(30L,1L),scope);
        assertThat(preview.candidates()).extracting(Candidate::agentId).containsExactly(12L);
        assertThat(service.randomUnbound(30L,rules,"manual:30","99","Manual confirmed",99L,preview).agentAdminId()).isEqualTo(12L);
        assigned.set(null);when(groups.candidates(1L)).thenReturn(List.of(candidate(11),candidate(13)));
        assertThatThrownBy(()->service.randomUnbound(30L,rules,"manual:31","99","Manual confirmed",99L,preview)).hasMessage("SUPPORT_RANDOM_SCOPE_CHANGED");
    }
    @Test void automaticRetryUsesStoredOriginalIdentityEvenAfterInviterTransferOrEligibilityChange(){
        setup(List.of(candidate(11)),excluded());when(mapper.autoEligible(30L)).thenReturn(true);
        service.retryAutomatic(30L);assertThat(assigned.get()).isNull();
        when(groups.candidates(1L)).thenReturn(List.of(candidate(12)));
        service.retryAutomatic(30L);assertThat(assigned.get().agentAdminId()).isEqualTo(12L);
        verify(mapper,never()).current(20L);verify(mapper,never()).invitationSnapshot(anyLong());
    }
    @Test void legacyDepthLimitWithoutOriginalFactCannotGuessOrAssignButOtherReasonsRemainEligible(){
        setup(List.of(candidate(12)),Map.of("reason","DEPTH_LIMIT"));
        assertThat(service.freezeRandomScope(new Customer(30L,1L),scope).candidates()).isEmpty();
        assertThat(service.randomUnbound(30L,rules,"manual:30","99","Manual confirmed",99L)).isNull();
        when(mapper.poolAttempt(30L)).thenReturn(Map.of("reason","NO_INVITER"));
        assertThat(service.randomUnbound(30L,rules,"manual:31","99","Manual confirmed",99L).agentAdminId()).isEqualTo(12L);
    }
    @Test void historicalPoolWithoutAutoEligibilityIsNeverMovedByRetry(){
        setup(List.of(candidate(12)),excluded());when(mapper.autoEligible(30L)).thenReturn(false);
        service.retryAutomatic(30L);assertThat(assigned.get()).isNull();verify(groups,never()).candidates(anyLong());
    }
    @Test void registrationPersistsOriginalAdvisorBeforeImmediateAutomaticDraw(){
        setup(List.of(candidate(11)),Map.of());
        var entered=new java.util.concurrent.atomic.AtomicBoolean();
        when(mapper.poolVersion(30L)).thenAnswer(c->entered.get()?1L:null);
        when(mapper.current(20L)).thenReturn(new SupportAssignment(100L,20L,11L,1L,"MANUAL",20L,0,null,1L));
        when(mapper.invitationSnapshot(20L)).thenReturn(Map.of("id",20L));
        when(mapper.enterDepthLimitedPool(30L,11L)).thenAnswer(c->{entered.set(true);return 1;});
        when(mapper.poolAttempt(30L)).thenAnswer(c->entered.get()?excluded():Map.of());
        when(production.productionSupportAutomationAllowed()).thenReturn(true);when(mapper.canonicalCustomer(30L)).thenReturn(1);
        service.register(30L,20L);
        assertThat(assigned.get()).isNull();verify(mapper).enterDepthLimitedPool(30L,11L);
        var order=inOrder(mapper);order.verify(mapper).enterDepthLimitedPool(30L,11L);order.verify(mapper).eligiblePool(30L,1L,"registration:30");
        verify(mapper,never()).enterPool(anyLong(),anyString());
    }
    @Test void supervisorModeStillPersistsExclusionForLaterExplicitPreviewWithoutAutomaticDraw(){
        setup(List.of(candidate(11)),Map.of());when(mapper.poolVersion(30L)).thenReturn(null);
        when(mapper.rules()).thenReturn(new SupportRules(1L,30,15,7,"LIMITED",0,"SUPERVISOR",null));
        when(mapper.current(20L)).thenReturn(new SupportAssignment(100L,20L,11L,1L,"MANUAL",20L,0,null,1L));
        when(mapper.invitationSnapshot(20L)).thenReturn(Map.of("id",20L));
        service.register(30L,20L);verify(mapper).enterDepthLimitedPool(30L,11L);
        verify(mapper,never()).eligiblePool(anyLong(),anyLong(),anyString());verify(groups,never()).candidates(anyLong());
    }

}
