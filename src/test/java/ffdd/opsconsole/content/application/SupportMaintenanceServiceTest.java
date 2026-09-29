package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportMaintenance.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportMaintenanceMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportMaintenanceServiceTest {
    final SupportMaintenanceMapper mapper=mock(SupportMaintenanceMapper.class);
    final SupportBindingMapper bindings=mock(SupportBindingMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final AdminIdempotencyService commands=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final SupportMaintenanceService maintenance=new SupportMaintenanceService(mapper,bindings,ownership,commands,audit);
    final SupportActivityService activity=new SupportActivityService(mapper,ownership,maintenance,mock(ProductionSupportPathGuard.class));
    SupportAssignment assignment=new SupportAssignment(10L,1L,2L,1L,"MANUAL",1L,0,null,null);
    LocalDateTime now=LocalDateTime.of(2026,9,29,0,0);
    Preference preference=new Preference(1L,true,1L);
    ActivityState state=new ActivityState(1L,0,null);
    Cycle open;
    final List<Cycle> closed=new ArrayList<>();
    final Map<Long,Long> messages=new HashMap<>();
    final Map<String,ActivityEvent> events=new HashMap<>();
    long cycleSequence;

    @BeforeEach void setup() {
        when(ownership.actorId()).thenReturn(2L);
        when(ownership.requireWriter(eq(1L),anyBoolean())).thenAnswer(i->assignment);
        when(bindings.current(1L)).thenAnswer(i->assignment);
        when(mapper.preference(1L)).thenAnswer(i->preference);
        when(mapper.captureFence()).thenAnswer(i->new SupportActivityService.Coverage(now.minusDays(10),now.minusSeconds(1)));
        when(mapper.eventTime()).thenAnswer(i->now);
        when(mapper.activity(1L)).thenAnswer(i->state);
        when(mapper.openCycle(1L)).thenAnswer(i->open);
        when(mapper.executionCustomer(anyLong())).thenAnswer(i->messages.get(i.getArgument(0)));
        when(mapper.insertCycle(anyLong(),anyLong(),anyLong(),anyLong(),any())).thenAnswer(i->{
            open=new Cycle(++cycleSequence,1L,i.getArgument(1),i.getArgument(2),"OPEN",i.getArgument(3),i.getArgument(4),i.getArgument(4),null,null);return 1;
        });
        when(mapper.insertExecution(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyString(),any())).thenAnswer(i->{messages.put(i.getArgument(4),i.getArgument(0));return 1;});
        when(mapper.touchCycle(anyLong(),any())).thenAnswer(i->{open=new Cycle(open.id(),1L,open.assignmentId(),open.agentAdminId(),"OPEN",open.baselineActivitySeq(),open.openedAt(),i.getArgument(1),null,null);return 1;});
        when(mapper.closeCycle(anyLong(),anyString(),any(),nullable(Long.class))).thenAnswer(i->{
            closed.add(new Cycle(open.id(),1L,open.assignmentId(),open.agentAdminId(),i.getArgument(1),open.baselineActivitySeq(),open.openedAt(),open.lastExecutionAt(),i.getArgument(2),i.getArgument(3)));open=null;return 1;
        });
        when(mapper.activityEvent(anyString())).thenAnswer(i->events.get(i.getArgument(0)));
        when(mapper.insertActivity(anyLong(),anyLong(),anyString(),any())).thenAnswer(i->{
            String source=i.getArgument(2);events.put(source,new ActivityEvent((long)events.size()+1,1L,i.getArgument(1),source,i.getArgument(3)));return 1;
        });
        when(mapper.updateActivity(anyLong(),anyLong(),any())).thenAnswer(i->{state=new ActivityState(1L,i.getArgument(1),i.getArgument(2));return 1;});
        when(mapper.changePreference(anyLong(),anyBoolean(),anyLong(),anyString(),anyLong())).thenAnswer(i->{preference=new Preference(1L,i.getArgument(1),preference.version()+1);return 1;});
        when(commands.executeRetained(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
    }
    void execute(long message) {maintenance.executed(1L,assignment,message,"command-key-"+message);}
    String login() {String session=UUID.randomUUID().toString();activity.interactiveLogin(1L,session);return session;}

    @Test void repeatedMessagesKeepBaselineAndDuplicateMessageHasNoEffect() {
        login(); execute(100); long baseline=open.baselineActivitySeq();
        now=now.plusDays(2);execute(101);execute(101);
        assertThat(messages).hasSize(2);assertThat(cycleSequence).isEqualTo(1);
        assertThat(open.baselineActivitySeq()).isEqualTo(baseline);
        assertThat(open.lastExecutionAt()).isEqualTo(now);assertThat(closed).isEmpty();
    }
    @Test void activeAndUnknownBothRequireANewEventAndSuccessCanBeFollowedImmediatelyByNewCycle() {
        String old=login();execute(100);activity.interactiveLogin(1L,old);
        assertThat(open).isNotNull();login();assertThat(closed).singleElement().extracting(Cycle::status).isEqualTo("SUCCEEDED");
        execute(101);assertThat(open.baselineActivitySeq()).isEqualTo(2);assertThat(cycleSequence).isEqualTo(2);
        activity.interactiveLogin(1L,old);assertThat(open).isNotNull();
        login();assertThat(closed).hasSize(2);assertThat(closed.get(0).successEventId()).isNotEqualTo(closed.get(1).successEventId());
    }
    @Test void unknownFirstContactOnlySucceedsOnFirstGenuineLogin() {
        execute(100);assertThat(open.baselineActivitySeq()).isZero();assertThat(closed).isEmpty();
        login();assertThat(closed.get(0).status()).isEqualTo("SUCCEEDED");
    }
    @Test void stoppedActivityDoesNotSucceedAndResumeDoesNotReviveOldCycle() {
        execute(100);maintenance.change(1L,false,"Customer requested pause",10L,1L,"stop-key-100");
        assertThat(closed.get(0).status()).isEqualTo("STOPPED");login();assertThat(closed).hasSize(1);
        assertThatThrownBy(()->execute(101)).hasMessageContaining("STOPPED");
        maintenance.change(1L,true,"Customer requested restart",10L,2L,"resume-key-100");
        assertThat(open).isNull();assertThat(messages).hasSize(1);execute(102);assertThat(open.baselineActivitySeq()).isEqualTo(1);
        verify(audit,times(2)).recordRequired(any());
    }
    @Test void transferClosesOldCyclePreservesStoppedPreferenceAndHistoricalOwner() {
        execute(100);SupportAssignment old=assignment;
        assignment=new SupportAssignment(11L,1L,3L,1L,"MANUAL",1L,0,null,null);
        maintenance.assignmentChanged(new SupportBindingService.SupportAssignmentChanged(1L,old,assignment));
        login();assertThat(closed).singleElement().satisfies(c->{assertThat(c.status()).isEqualTo("TRANSFERRED");assertThat(c.agentAdminId()).isEqualTo(2);});
        preference=new Preference(1L,false,2L);
        maintenance.assignmentChanged(new SupportBindingService.SupportAssignmentChanged(1L,old,assignment));
        assertThat(preference.enabled()).isFalse();
    }
    @Test void lateEventWithOldTimeOrBaselineCannotCompleteCycle() {
        execute(100);
        maintenance.effectiveActivity(new ActivityEvent(1L,1L,1,"old",now.minusNanos(1000)));
        maintenance.effectiveActivity(new ActivityEvent(2L,1L,0,"baseline",now));
        assertThat(closed).isEmpty();
        maintenance.effectiveActivity(new ActivityEvent(3L,1L,1,"same-microsecond-new-seq",now));
        assertThat(closed.get(0).successEventId()).isEqualTo(3);
    }
    @Test void stalePreferenceOrAssignmentCannotMutateAndStoppedDoesNotLimitServiceMessages() {
        assertThatThrownBy(()->maintenance.change(1L,false,"Requested pause",11L,1L,"stop-key-200")).hasMessageContaining("ASSIGNMENT_CHANGED");
        assertThatThrownBy(()->maintenance.change(1L,false,"Requested pause",10L,2L,"stop-key-201")).hasMessageContaining("VERSION_CONFLICT");
        verify(mapper,never()).changePreference(anyLong(),anyBoolean(),anyLong(),anyString(),anyLong());
    }
    @Test void currentStateReadsAndWritesAreAfterCustomerLock() {
        execute(100);
        var order=inOrder(ownership,mapper);
        order.verify(ownership).requireWriter(1L,true);
        order.verify(mapper).executionCustomer(100L);
        order.verify(mapper).preference(1L);
        order.verify(mapper).captureFence();
        order.verify(mapper).openCycle(1L);
        order.verify(mapper).activity(1L);
    }
    @Test void missingCaptureFenceFailsClosedBeforeActivityMutation() {
        when(mapper.captureFence()).thenReturn(null);
        assertThatThrownBy(this::login).hasMessageContaining("CAPTURE_UNAVAILABLE");
        verify(mapper,never()).insertActivity(anyLong(),anyLong(),anyString(),any());
    }
    @Test void futureClockRegressionDoesNotBackdateAnEvent() {
        state=new ActivityState(1L,1,now.plusSeconds(1));
        assertThatThrownBy(this::login).hasMessageContaining("CLOCK_REGRESSED");
        assertThat(events).isEmpty();
    }
    @Test void coverageRejectsGapStaleWatermarkAndLongerWindowUntilRecoveryIsContinuous() {
        var first=new SupportActivityService.Coverage(now.minusDays(30),now);
        assertThat(first.covers(now.minusDays(7),now)).isTrue();
        var gap=new SupportActivityService.Coverage(now.minusDays(2),now);
        assertThat(gap.covers(now.minusDays(7),now)).isFalse();
        var recovered=new SupportActivityService.Coverage(now.minusDays(7),now);
        assertThat(recovered.covers(now.minusDays(7),now)).isTrue();
        assertThat(recovered.covers(now.minusDays(8),now)).isFalse();
        assertThat(recovered.covers(now.minusDays(7),now.plusNanos(1000))).isFalse();
    }
    @Test void checkpointNeverTakesCustomerLocksAndReturnsCommittedFenceRatherThanWallClock() {
        var start=new SupportActivityService.Coverage(now.minusDays(1),now.minusMinutes(1));
        var advanced=new SupportActivityService.Coverage(start.coverageStartAt(),now);
        when(mapper.checkpointFence()).thenReturn(start,advanced);when(mapper.advanceWatermark()).thenReturn(1);
        assertThat(activity.checkpoint()).isEqualTo(advanced);verifyNoInteractions(ownership);
    }
    @Test void mutationBoundariesRequireCallingTransaction() throws Exception {
        assertThat(SupportMaintenanceService.class.getMethod("executed",Long.class,SupportAssignment.class,Long.class,String.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(SupportActivityService.class.getMethod("interactiveLogin",Long.class,String.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(SupportMaintenanceService.class.getMethod("assignmentChanged",SupportBindingService.SupportAssignmentChanged.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
