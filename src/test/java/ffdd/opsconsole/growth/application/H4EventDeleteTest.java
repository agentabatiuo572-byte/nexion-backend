package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GrowthEventDeleteRequest;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static ffdd.opsconsole.growth.application.H4EventRuleService.values;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H4EventDeleteTest {
    private final H4EventRuleRepository db=mock(H4EventRuleRepository.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final A2RuntimePolicy a2=mock(A2RuntimePolicy.class);
    private final H4EventRuleService service=new H4EventRuleService(db,new ObjectMapper(),audit,null,null,a2);
    private final LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);

    @Test void onlyNeverPublishedDraftsAndActuallyEndedActivitiesAreDeletable(){
        var snapshot=snapshot();
        assertState(snapshot,"draft",true);
        snapshot.put("rule_activated_at",now.minusDays(1));
        snapshot.put("rule_starts_at",now.plusDays(1));
        assertState(snapshot,"upcoming",false);
        snapshot.put("rule_starts_at",now.minusDays(1));
        snapshot.put("status",0); // Disabled/closed presentation is not an explicit end.
        assertState(snapshot,"ongoing",false);
        snapshot.put("rule_ends_at",now);
        assertState(snapshot,"ended",true); // Exclusive end boundary.
        snapshot.remove("rule_activated_at");
        snapshot.remove("rule_starts_at");
        snapshot.remove("rule_ends_at");
        snapshot.put("status",1);
        snapshot.put("starts_at",now.plusDays(1));
        assertState(snapshot,"upcoming",false); // Published legacy row with no rule.
    }

    @Test void explicitTerminalEndCanBeDeletedBeforeTheOriginalDeadline(){
        var snapshot=snapshot();
        snapshot.put("status",2);
        snapshot.put("rule_activated_at",now.minusDays(1));
        snapshot.put("rule_enabled",0);
        snapshot.put("rule_starts_at",now.minusDays(1));
        snapshot.put("rule_ends_at",now.plusDays(2));
        assertState(snapshot,"ended",true);
        stub(snapshot);
        when(db.write("UPDATE nx_event_quest SET is_deleted=1,updated_at=NOW() WHERE quest_code=? AND is_deleted=0","evt-test")).thenReturn(1);
        assertThat(service.delete("evt-test",request(snapshot)).getData()).containsEntry("deleted",true);
    }

    @Test void staleSnapshotOrRunningActivityNeverWritesOrAudits(){
        var snapshot=snapshot();stub(snapshot);
        assertThatThrownBy(()->service.delete("evt-test",new GrowthEventDeleteRequest("stale","cleanup")))
                .isInstanceOf(BizException.class).hasMessageContaining("EVENT_CONFIG_STALE");
        snapshot.put("rule_activated_at",now.minusDays(1));
        snapshot.put("rule_starts_at",now.minusDays(1));
        assertThatThrownBy(()->service.delete("evt-test",request(snapshot)))
                .isInstanceOf(BizException.class).hasMessageContaining("EVENT_DELETE_STATE_INVALID");
        verify(db,never()).write(anyString(),any(Object[].class));
        verifyNoInteractions(audit);
    }

    @Test void softDeletionRetainsAllEconomicFactsAndRejectsASecondDeletion(){
        var snapshot=snapshot();stub(snapshot);
        when(db.write("UPDATE nx_event_quest SET is_deleted=1,updated_at=NOW() WHERE quest_code=? AND is_deleted=0","evt-test")).thenReturn(1);
        var result=service.delete("evt-test",request(snapshot));
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("deleted",true);
        verify(db).write("UPDATE nx_event_quest SET is_deleted=1,updated_at=NOW() WHERE quest_code=? AND is_deleted=0","evt-test");
        verify(audit).recordRequired(any());
        verify(db,never()).write(startsWith("DELETE"),any(Object[].class));
        snapshot.put("is_deleted",1);
        assertThatThrownBy(()->service.delete("evt-test",request(snapshot)))
                .isInstanceOf(BizException.class).hasMessageContaining("EVENT_NOT_FOUND");
    }

    @Test void optimisticVersionDetectsRuleAndQuestChangesEvenWithinOneSecond(){
        var snapshot=snapshot();var initial=request(snapshot).expectedVersion();
        snapshot.put("rule_revision",2L);
        assertThat(request(snapshot).expectedVersion()).isNotEqualTo(initial);
        snapshot.remove("rule_revision");snapshot.put("reward_amount",50);
        assertThat(request(snapshot).expectedVersion()).isNotEqualTo(initial);
    }

    @Test void tombstonedActivityCannotBeRepublishedThroughTheRuleEndpoint(){
        when(a2.reasonMinChars()).thenReturn(3);
        when(db.row("SELECT * FROM nx_growth_event_rule WHERE event_code=? FOR UPDATE","evt-test"))
                .thenReturn(values("revision",1L));
        when(db.row("SELECT 1 FROM nx_event_quest WHERE quest_code=? AND is_deleted=1 FOR UPDATE","evt-test"))
                .thenReturn(Map.of("1",1));
        assertThatThrownBy(()->service.update("evt-test",new ffdd.opsconsole.growth.dto.H4EventRuleRequest(1,true,null,null,Map.of(),"republish")))
                .isInstanceOf(BizException.class).hasMessageContaining("EVENT_NOT_FOUND");
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    private Map<String,Object> snapshot(){return values("quest_code","evt-test","status",0,"is_deleted",0,"ends_at",now.plusDays(2));}
    private GrowthEventDeleteRequest request(Map<String,Object> snapshot){return new GrowthEventDeleteRequest((String)service.deletionInfo(snapshot,now).get("deleteVersion"),"cleanup");}
    private void assertState(Map<String,Object> snapshot,String state,boolean allowed){
        assertThat(service.deletionInfo(snapshot,now)).containsEntry("deleteState",state).containsEntry("deletable",allowed);
    }
    private void stub(Map<String,Object> snapshot){
        when(a2.reasonMinChars()).thenReturn(3);
        when(db.row(startsWith("SELECT q.*"),eq("evt-test"))).thenReturn(snapshot);
    }
}
