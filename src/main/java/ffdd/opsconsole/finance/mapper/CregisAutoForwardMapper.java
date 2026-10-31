package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisAutoForwardMapper extends BaseMapper<Object> {
    String FIELDS="CAST(id AS CHAR) id,CAST(event_id AS CHAR) eventId,source_address sourceAddress,platform_address platformAddress,"
        +"counterparty_address counterpartyAddress,CAST(gross_amount AS CHAR) grossAmount,CAST(net_amount AS CHAR) netAmount,CAST(fee_amount AS CHAR) feeAmount,"
        +"payment_key paymentKey,state,CAST(collection_cid AS CHAR) collectionCid,collection_txid collectionTxid,collection_floor collectionFloor,"
        +"CAST(payment_cid AS CHAR) paymentCid,payment_txid paymentTxid,payment_floor paymentFloor,intent_at intentAt,last_error lastError,version";
    String POLICY="collection_enabled collectionEnabled,payment_enabled paymentEnabled,CAST(start_event_id AS CHAR) startEventId,version,"
        +"pending_collection pendingCollection,pending_payment pendingPayment,CAST(maker_id AS CHAR) makerId,reason,proposed_at proposedAt";
    @Select("SELECT "+POLICY+" FROM nx_cregis_auto_forward_policy WHERE project_id=#{project}")
    Map<String,Object> policy(long project);
    @Select("SELECT "+POLICY+" FROM nx_cregis_auto_forward_policy WHERE project_id=#{project} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockPolicy(long project);
    @Update("UPDATE nx_cregis_auto_forward_policy SET pending_collection=#{collect},pending_payment=#{pay},maker_id=#{actor},reason=#{reason},proposed_at=NOW(),version=version+1 WHERE project_id=#{project} AND version=#{version} AND maker_id IS NULL")
    int propose(@Param("project") long project,@Param("version") long version,@Param("collect") int collect,@Param("pay") int pay,@Param("actor") long actor,@Param("reason") String reason);
    @Update("UPDATE nx_cregis_auto_forward_policy SET collection_enabled=#{collect},payment_enabled=#{pay},start_event_id=IF(#{collect}=1 OR #{pay}=1,COALESCE(start_event_id,(SELECT COALESCE(MAX(id),0) FROM nx_cregis_deposit_event WHERE project_id=#{project})),start_event_id),pending_collection=NULL,pending_payment=NULL,maker_id=NULL,reason=#{reason},proposed_at=NULL,version=version+1 WHERE project_id=#{project} AND version=#{version}")
    int apply(@Param("project") long project,@Param("version") long version,@Param("collect") int collect,@Param("pay") int pay,@Param("reason") String reason);
    @Update("UPDATE nx_cregis_auto_forward_policy SET pending_collection=NULL,pending_payment=NULL,maker_id=NULL,proposed_at=NULL,version=version+1 WHERE project_id=#{project} AND version=#{version}")
    int cancel(@Param("project") long project,@Param("version") long version);
    @Update("UPDATE nx_cregis_auto_forward_policy SET collection_enabled=0,payment_enabled=0,pending_collection=NULL,pending_payment=NULL,maker_id=NULL,proposed_at=NULL,reason=#{reason},version=version+1 WHERE project_id=#{project}")
    int pause(@Param("project") long project,@Param("reason") String reason);
    @Select("SELECT e.id FROM nx_cregis_deposit_event e WHERE e.project_id=#{project} AND e.chain_id='2510' AND e.status='CREDITED' AND e.id>#{start} AND NOT EXISTS(SELECT 1 FROM nx_cregis_auto_forward_task t WHERE t.project_id=e.project_id AND (t.event_id=e.id OR t.active_source=e.address)) ORDER BY e.id LIMIT 20")
    List<Long> candidates(@Param("project") long project,@Param("start") long start);
    @Select("SELECT e.id,e.user_id userId,e.txid,e.log_index logIndex,e.address,e.gross_amount grossAmount,e.net_amount netAmount,e.fee_amount feeAmount,e.block_number blockNumber,e.status FROM nx_cregis_deposit_event e JOIN nx_cregis_deposit_address a ON a.project_id=e.project_id AND a.address=e.address AND a.user_id=e.user_id AND a.chain_id='2510' AND a.state='READY' WHERE e.project_id=#{project} AND e.chain_id='2510' AND e.id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockEvent(@Param("project") long project,@Param("id") long id);
    @Insert("INSERT INTO nx_cregis_auto_forward_task(project_id,event_id,source_address,platform_address,counterparty_address,gross_amount,net_amount,fee_amount,payment_key) VALUES(#{project},#{event.id},#{event.address},#{platform},#{counterparty},#{event.grossAmount},#{event.netAmount},#{event.feeAmount},#{key})")
    int enqueue(@Param("project") long project,@Param("event") Map<String,Object> event,@Param("platform") String platform,@Param("counterparty") String counterparty,@Param("key") String key);
    @Select("SELECT "+FIELDS+" FROM nx_cregis_auto_forward_task WHERE project_id=#{project} AND id>#{after} AND state NOT IN ('COMPLETED','COLLECTION_FAILED','PAYMENT_FAILED') ORDER BY id LIMIT 20")
    List<Map<String,Object>> work(@Param("project") long project,@Param("after") long after);
    @Select("SELECT "+FIELDS+" FROM nx_cregis_auto_forward_task WHERE project_id=#{project} AND (#{before}=0 OR id<#{before}) ORDER BY id DESC LIMIT #{limit}")
    List<Map<String,Object>> page(@Param("project") long project,@Param("before") long before,@Param("limit") int limit);
    @Select("SELECT "+FIELDS+" FROM nx_cregis_auto_forward_task WHERE project_id=#{project} AND id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lock(@Param("project") long project,@Param("id") long id);
    @Select("SELECT "+FIELDS+" FROM nx_cregis_auto_forward_task WHERE project_id=#{project} AND payment_key=#{key}")
    Map<String,Object> byKey(@Param("project") long project,@Param("key") String key);
    @Update("UPDATE nx_cregis_auto_forward_task SET state=#{state},collection_cid=#{r.collectionCid},collection_txid=#{r.collectionTxid},collection_floor=#{r.collectionFloor},payment_cid=#{r.paymentCid},payment_txid=#{r.paymentTxid},payment_floor=#{r.paymentFloor},intent_at=#{r.intentAt},last_error=#{error},version=version+1 WHERE project_id=#{project} AND id=#{r.id} AND version=#{r.version}")
    int save(@Param("project") long project,@Param("r") Map<String,Object> row,@Param("state") String state,@Param("error") String error);
    @Insert("INSERT INTO nx_cregis_risk_alert(project_id,alert_key,severity,kind,evidence) VALUES(#{project},CONCAT('auto-forward-',#{id}),'P1','AUTO_FORWARD_UNKNOWN',JSON_OBJECT('taskId',#{id},'reason',#{reason})) ON DUPLICATE KEY UPDATE resolved_at=NULL,evidence=VALUES(evidence)")
    int holdUnknown(@Param("project") long project,@Param("id") long id,@Param("reason") String reason);
    @Update("UPDATE nx_cregis_risk_alert SET resolved_at=NOW() WHERE project_id=#{project} AND alert_key=CONCAT('auto-forward-',#{id}) AND kind='AUTO_FORWARD_UNKNOWN' AND resolved_at IS NULL")
    int resolveUnknown(@Param("project") long project,@Param("id") long id);
}
