package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.*;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Header;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RegionalWeeklyEventFlowTest {
    private static final LocalDateTime AT=LocalDateTime.parse("2026-10-26T00:30:00");
    private static final String VN="WEEK:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CN="WEEK:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TYPE="H3_GENESIS_SECONDARY_MARKET_VIEWED";
    private static final String CODE="weekly_t2_genesis_browse";
    private final WeeklyQuestSnapshotService weekly=mock(WeeklyQuestSnapshotService.class);
    private final QuestCanonicalEventBindingMapper bindings=mock(QuestCanonicalEventBindingMapper.class);
    private final EventConsumerDeliveryService deliveries=mock(EventConsumerDeliveryService.class);
    private final ObjectMapper json=new ObjectMapper();

    private EventOutboxMessage event(String id,long user,String key) {
        var event=new EventOutboxMessage();
        event.setEventId(id);event.setEventType(TYPE);event.setEventTs(AT);event.setServerAuthoritative(true);
        event.setPayload("{\"user_id\":"+user+",\"instanceKey\":\""+key+"\"}");
        return event;
    }
    private void claim(EventOutboxMessage event) {
        when(deliveries.claim(event,QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC,event.getEventId(),0)).thenReturn(
                new EventConsumerDeliveryService.ConsumerClaim(true,event.getEventId(),"PROCESSING",1));
    }
    private Header header(String key) {
        return new Header(key,AT.minusDays(6),AT.plusMinutes(30),BigDecimal.ONE,3,1);
    }

    @Test void sameInstantUsesFrozenAccountWindowInsteadOfGlobalWeek() {
        var projector=mock(QuestCanonicalEventProjector.class);
        var consumer=new QuestCanonicalEventConsumer(bindings,null,deliveries,projector,json,weekly);
        var vn=event("vn",84,VN);var cn=event("cn",86,CN);
        when(weekly.publishedFor(84L,AT,VN)).thenReturn(true);
        when(weekly.publishedFor(86L,AT,CN)).thenReturn(false);
        claim(vn);claim(cn);
        consumer.onOutboxMessage(vn);consumer.onOutboxMessage(cn);
        verify(projector).project(vn,"vn");verify(projector,never()).project(cn,"cn");
        verify(deliveries).markSkipped(QuestCanonicalEventConsumer.CONSUMER_GROUP,"cn","H3_WEEKLY_FACT_OUTSIDE_CURRENT_WEEK");
        verify(weekly,never()).publishedFor(AT);
    }

    @Test void expiredPendingFactResumesSameDeliveryAndTerminatesOnce() {
        var projector=mock(QuestCanonicalEventProjector.class);
        var consumer=new QuestCanonicalEventConsumer(bindings,null,deliveries,projector,json,weekly);
        var event=event("late",84,VN);
        when(deliveries.claim(event,QuestCanonicalEventConsumer.CONSUMER_GROUP,QuestCanonicalEventConsumer.TOPIC,"late",0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false,"late","PENDING_BINDING",1),
                        new EventConsumerDeliveryService.ConsumerClaim(false,"late","SKIPPED",1));
        when(deliveries.resumePendingBinding(QuestCanonicalEventConsumer.CONSUMER_GROUP,"late")).thenReturn(true);
        consumer.onOutboxMessage(event);consumer.onOutboxMessage(event);
        verify(deliveries).markSkipped(QuestCanonicalEventConsumer.CONSUMER_GROUP,"late","H3_WEEKLY_FACT_OUTSIDE_CURRENT_WEEK");
        verifyNoInteractions(projector);
    }

    @Test void frozenCompletionCarriesSourceMissionAndInstanceAndNeverUsesOtherBeneficiary() {
        var facts=mock(QuestCompletionFactConsumer.class);
        var projector=new QuestCanonicalEventProjector(bindings,null,facts,deliveries,json,weekly);
        when(weekly.hasEventBindings(TYPE)).thenReturn(true);
        when(weekly.lockAccountsForProjection(any())).thenAnswer(call->call.getArgument(0));
        var binding=new CanonicalQuestEventBinding("FROZEN","SYSTEM",TYPE,CODE,"user_id","WEEKLY_T2");
        var event=event("one",84,VN);
        event.setPayload("{\"user_id\":84,\"inviter_user_id\":86,\"instanceKey\":\""+VN+"\"}");
        when(weekly.bindings(84L,TYPE,AT,VN)).thenReturn(List.of(binding));
        when(weekly.bindings(86L,TYPE,AT,VN)).thenReturn(List.of(binding));
        when(weekly.mission(84L,CODE,AT,VN)).thenReturn(new MissionDefinition(9L,CODE,"WEEKLY_T2",VN));
        projector.project(event,"one");
        var command=org.mockito.ArgumentCaptor.forClass(QuestCompletionFactConsumer.QuestCompletionCommand.class);
        verify(facts).consume(command.capture());
        assertThat(command.getValue()).satisfies(c->{
            assertThat(c.userId()).isEqualTo(84L);assertThat(c.instanceKey()).isEqualTo(VN);
            assertThat(c.sourceMissionId()).isEqualTo(9L);assertThat(c.eventId()).startsWith("WQ:").hasSize(67);
        });
        verify(weekly,never()).mission(86L,CODE,AT,VN);
        verify(deliveries).markSuccess(QuestCanonicalEventConsumer.CONSUMER_GROUP,"one",1);
    }

    @Test void genericSharedBindingCodeHasDistinctIdempotencyIdentityPerRegionalBeneficiary() {
        var facts=mock(QuestCompletionFactConsumer.class);
        var projector=new QuestCanonicalEventProjector(bindings,null,facts,deliveries,json,weekly);
        String eventType="CUSTOM_WEEKLY_FACT";
        var event=event("shared",84,VN);event.setEventType(eventType);
        event.setPayload("{\"user_id\":84,\"inviter_user_id\":86}");
        when(weekly.hasEventBindings(eventType)).thenReturn(true);
        when(weekly.lockAccountsForProjection(List.of(84L,86L))).thenReturn(List.of(84L,86L));
        for (long user:List.of(84L,86L)) {
            String field=user==84?"user_id":"inviter_user_id",key=user==84?VN:CN;
            var binding=new CanonicalQuestEventBinding("SAME","SYSTEM",eventType,"custom",field,"WEEKLY_T2");
            when(weekly.bindings(user,eventType,AT,null)).thenReturn(List.of(binding));
            when(weekly.mission(user,"custom",AT,null)).thenReturn(new MissionDefinition(9L,"custom","WEEKLY_T2",key));
        }
        projector.project(event,"shared");
        var commands=org.mockito.ArgumentCaptor.forClass(QuestCompletionFactConsumer.QuestCompletionCommand.class);
        verify(facts,times(2)).consume(commands.capture());
        assertThat(commands.getAllValues()).extracting(c->c.userId()).containsExactly(84L,86L);
        assertThat(commands.getAllValues()).extracting(c->c.eventId()).doesNotHaveDuplicates();
    }

    @Test void inactiveSecondaryCandidateDoesNotBlockAnActiveGenericBeneficiary() {
        var facts=mock(QuestCompletionFactConsumer.class);
        var projector=new QuestCanonicalEventProjector(bindings,null,facts,deliveries,json,weekly);
        String eventType="CUSTOM_WEEKLY_FACT";
        var event=event("active",84,VN);event.setEventType(eventType);
        event.setPayload("{\"user_id\":84,\"inviter_user_id\":86}");
        when(weekly.hasEventBindings(eventType)).thenReturn(true);
        when(weekly.lockAccountsForProjection(List.of(84L,86L))).thenReturn(List.of(84L));
        when(weekly.bindings(84L,eventType,AT,null)).thenReturn(List.of(new CanonicalQuestEventBinding("X","SYSTEM",eventType,"custom","user_id","WEEKLY_T2")));
        when(weekly.mission(84L,"custom",AT,null)).thenReturn(new MissionDefinition(9L,"custom","WEEKLY_T2",VN));
        projector.project(event,"active");
        verify(facts).consume(any());verify(weekly,never()).bindings(86L,eventType,AT,null);
    }

    @Test void regionalThresholdWithoutFrozenKeyCannotJoinTheCurrentWeek() {
        var projector=mock(QuestCanonicalEventProjector.class);
        var consumer=new QuestCanonicalEventConsumer(bindings,null,deliveries,projector,json,weekly);
        var event=event("missing-key",84,VN);event.setPayload("{\"user_id\":84}");
        when(weekly.current(84L)).thenReturn(new Header(VN,AT.minusDays(1),AT.plusMinutes(30),BigDecimal.ONE,3,1,"Asia/Ho_Chi_Minh",AT.toLocalDate().minusDays(1),"REGION_V1"));
        claim(event);consumer.onOutboxMessage(event);
        verify(deliveries).markSkipped(QuestCanonicalEventConsumer.CONSUMER_GROUP,"missing-key","H3_WEEKLY_INSTANCE_REQUIRED");
        verifyNoInteractions(projector);
    }

    @Test void conflictingCanonicalInstanceAliasesCannotSelectDifferentWeeks() {
        var event=event("aliases",84,VN);
        event.setPayload("{\"user_id\":84,\"instanceKey\":\""+VN+"\",\"instance_key\":\""+CN+"\"}");
        var consumer=new QuestCanonicalEventConsumer(bindings,null,deliveries,mock(QuestCanonicalEventProjector.class),json,weekly);
        assertThatThrownBy(()->consumer.onOutboxMessage(event)).hasMessage("QUEST_CANONICAL_INSTANCE_MISMATCH");
        verifyNoInteractions(weekly,deliveries);
    }

    @Test void thresholdObservationsStayOnAssignedKeysForBothRegions() {
        var mapper=mock(H3WeeklyParticipationMapper.class);var outbox=mock(EventOutboxService.class);
        var evaluator=new H3WeeklyParticipationEvaluator(mapper,outbox,Clock.fixed(AT.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC),weekly);
        for (var entry:Map.of(84L,VN,86L,CN).entrySet()) {
            var h=header(entry.getValue());when(weekly.current(entry.getKey())).thenReturn(h);
            when(weekly.eligible(entry.getKey(),h,AT)).thenReturn(true);
            when(mapper.lockThreshold(entry.getKey(),entry.getValue(),TYPE)).thenReturn(new H3WeeklyParticipationMapper.ThresholdRow(entry.getKey(),null));
            when(mapper.insertObservation(entry.getKey(),entry.getValue(),"GENESIS_SECONDARY_MARKET","SECONDARY_MARKET",AT)).thenReturn(1);
            assertThat(evaluator.recordGenesisSecondaryMarketView(entry.getKey())).isTrue();
            verify(mapper).insertObservation(entry.getKey(),entry.getValue(),"GENESIS_SECONDARY_MARKET","SECONDARY_MARKET",AT);
        }
    }

    @Test void explicitFrozenMissionSurvivesPhysicalLiveTemplateRemoval() {
        var mapper=mock(QuestCompletionFactMapper.class);var audit=mock(AuditLogService.class);var outbox=mock(EventOutboxService.class);
        var consumer=new QuestCompletionFactConsumer(mapper,audit,outbox,null,null,weekly);
        when(mapper.lockActiveUser(84L)).thenReturn(84L);
        when(weekly.mission(84L,CODE,AT,VN)).thenReturn(new MissionDefinition(9L,CODE,"WEEKLY_T2",VN));
        when(weekly.publishedFor(84L,AT,VN)).thenReturn(true);
        when(mapper.lockUserMissionStatus(84L,9L,VN)).thenReturn("CLAIMED");
        var result=consumer.consume(new QuestCompletionFactConsumer.QuestCompletionCommand("SYSTEM","frozen",84L,CODE,AT,9L,VN));
        assertThat(result.replay()).isTrue();
        verify(mapper,never()).lockDayOneSnapshotMissionAt(any(),any(),any(),any(),any());
        verify(mapper,never()).lockMissionInstanceAt(any(),any(),any());
    }
}
