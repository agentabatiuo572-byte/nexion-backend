package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportMaintenance.*;
import ffdd.opsconsole.content.application.SupportActivityService.Coverage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportMaintenanceCycleEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface SupportMaintenanceMapper extends BaseMapper<SupportMaintenanceCycleEntity> {
    @Select("SELECT coverage_start_at coverageStartAt,observed_through_at observedThroughAt FROM nx_support_activity_coverage WHERE id=1 FOR SHARE")
    Coverage captureFence();
    @Select("SELECT coverage_start_at coverageStartAt,observed_through_at observedThroughAt FROM nx_support_activity_coverage WHERE id=1 FOR UPDATE")
    Coverage checkpointFence();
    @Update("UPDATE nx_support_activity_coverage SET observed_through_at=GREATEST(UTC_TIMESTAMP(6),TIMESTAMPADD(MICROSECOND,1,observed_through_at)) WHERE id=1")
    int advanceWatermark();
    @Select("SELECT GREATEST(UTC_TIMESTAMP(6),TIMESTAMPADD(MICROSECOND,1,observed_through_at)) FROM nx_support_activity_coverage WHERE id=1 FOR SHARE")
    LocalDateTime eventTime();
    @Insert("INSERT IGNORE INTO nx_support_activity_state(customer_id,activity_seq) VALUES(#{customer},0)")
    int initializeActivity(Long customer);
    @Select("SELECT customer_id customerId,activity_seq activitySeq,last_effective_at lastEffectiveAt FROM nx_support_activity_state WHERE customer_id=#{customer} FOR SHARE")
    ActivityState activity(Long customer);
    @Select("SELECT id,customer_id customerId,seq,source_ref sourceRef,occurred_at occurredAt FROM nx_support_activity_event WHERE source_ref=#{source} FOR SHARE")
    ActivityEvent activityEvent(String source);
    @Insert("INSERT INTO nx_support_activity_event(customer_id,seq,source_ref,occurred_at) VALUES(#{customer},#{seq},#{source},#{at})")
    int insertActivity(@Param("customer") Long customer,@Param("seq") long seq,@Param("source") String source,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_activity_state SET activity_seq=#{seq},last_effective_at=#{at} WHERE customer_id=#{customer}")
    int updateActivity(@Param("customer") Long customer,@Param("seq") long seq,@Param("at") LocalDateTime at);
    @Select("SELECT customer_id customerId,enabled,version FROM nx_support_maintenance_preference WHERE customer_id=#{customer} FOR SHARE")
    Preference preference(Long customer);
    @Insert("INSERT IGNORE INTO nx_support_maintenance_preference(customer_id,updated_at) VALUES(#{customer},UTC_TIMESTAMP(6))")
    int initializePreference(Long customer);
    @Update("UPDATE nx_support_maintenance_preference SET enabled=#{enabled},version=version+1,updated_by=#{actor},reason=#{reason},updated_at=UTC_TIMESTAMP(6) WHERE customer_id=#{customer} AND version=#{version}")
    int changePreference(@Param("customer") Long customer,@Param("enabled") boolean enabled,@Param("actor") Long actor,@Param("reason") String reason,@Param("version") Long version);
    String CYCLE_COLUMNS="id,customer_id customerId,assignment_id assignmentId,agent_admin_id agentAdminId,status,baseline_activity_seq baselineActivitySeq,opened_at openedAt,last_execution_at lastExecutionAt,closed_at closedAt,success_event_id successEventId";
    @Select("SELECT "+CYCLE_COLUMNS+" FROM nx_support_maintenance_cycle WHERE customer_id=#{customer} AND status='OPEN' FOR SHARE")
    Cycle openCycle(Long customer);
    @Select("SELECT customer_id FROM nx_support_maintenance_execution WHERE message_id=#{message} FOR SHARE")
    Long executionCustomer(Long message);
    @Insert("INSERT INTO nx_support_maintenance_cycle(customer_id,assignment_id,agent_admin_id,status,baseline_activity_seq,opened_at,last_execution_at) VALUES(#{customer},#{assignment},#{agent},'OPEN',#{baseline},#{at},#{at})")
    int insertCycle(@Param("customer") Long customer,@Param("assignment") Long assignment,@Param("agent") Long agent,@Param("baseline") long baseline,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO nx_support_maintenance_execution(customer_id,assignment_id,agent_admin_id,cycle_id,message_id,command_key,executed_at) VALUES(#{customer},#{assignment},#{agent},#{cycle},#{message},#{key},#{at})")
    int insertExecution(@Param("customer") Long customer,@Param("assignment") Long assignment,@Param("agent") Long agent,@Param("cycle") Long cycle,@Param("message") Long message,@Param("key") String key,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_maintenance_cycle SET last_execution_at=#{at} WHERE id=#{id} AND status='OPEN'")
    int touchCycle(@Param("id") Long id,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_maintenance_cycle SET status=#{status},closed_at=#{at},success_event_id=#{event} WHERE id=#{id} AND status='OPEN'")
    int closeCycle(@Param("id") Long id,@Param("status") String status,@Param("at") LocalDateTime at,@Param("event") Long event);
    @Select("SELECT "+CYCLE_COLUMNS+" FROM nx_support_maintenance_cycle WHERE customer_id=#{customer} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Cycle> cycles(@Param("customer") Long customer,@Param("offset") long offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM nx_support_maintenance_cycle WHERE customer_id=#{customer}")
    long cycleCount(Long customer);
    @Select("SELECT id,customer_id customerId,assignment_id assignmentId,agent_admin_id agentAdminId,cycle_id cycleId,message_id messageId,executed_at executedAt FROM nx_support_maintenance_execution WHERE customer_id=#{customer} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> executions(@Param("customer") Long customer,@Param("offset") long offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM nx_support_maintenance_execution WHERE customer_id=#{customer}")
    long executionCount(Long customer);
}
