package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisReorgResolutionMapper extends BaseMapper<Object> {
    String ROW = "SELECT r.incident_id id,r.event_id eventId,r.project_id projectId,r.cid,r.user_id userId,"
        + "r.net_amount netAmount,r.held_amount heldAmount,r.recovered_amount recoveredAmount,"
        + "r.outstanding_debt outstandingDebt,r.status,r.version,e.txid,e.log_index logIndex,e.address,"
        + "e.gross_amount grossAmount,e.block_number blockNumber,e.block_hash blockHash,e.ledger_id ledgerId,"
        + "e.origin_log_index originalLogIndex,e.transfer_from_address transferFrom,"
        + "e.chain_id chainId,e.status eventStatus,u.status userStatus,u.c2_freeze_source freezeSource,u.c2_freeze_source_ref freezeRef "
        + "FROM nx_cregis_reorg_resolution r JOIN nx_cregis_deposit_event e ON e.id=r.event_id JOIN nx_user u ON u.id=r.user_id";
    @Select(ROW+" WHERE r.project_id=#{projectId} AND (#{before}=0 OR r.incident_id<#{before}) ORDER BY r.incident_id DESC LIMIT #{limit}")
    List<Map<String,Object>> incidents(@Param("projectId") long projectId,@Param("before") long before,@Param("limit") int limit);
    @Select(ROW+" WHERE r.incident_id=#{id} AND r.project_id=#{projectId}")
    Map<String,Object> incident(@Param("projectId") long projectId,@Param("id") long id);
    @Select(ROW+" WHERE r.incident_id=#{id} AND r.project_id=#{projectId} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockIncident(@Param("projectId") long projectId,@Param("id") long id);
    @Select("SELECT id,status,c2_freeze_source freezeSource,c2_freeze_source_ref freezeRef FROM nx_user WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    Map<String,Object> lockUser(long id);
    @Select("SELECT id,user_id userId,usdt_available usdtAvailable,nex_available nexAvailable,pending_withdraw pendingWithdraw,"
        +"cregis_risk_held cregisRiskHeld,cumulative_deposit_usdt cumulativeDeposit,version,fund_incarnation incarnation,fund_revision revision,sandbox,is_deleted isDeleted,created_at createdAt "
        +"FROM nx_user_wallet WHERE user_id=#{id} AND is_deleted=0 FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> wallet(long id);
    @Update("UPDATE nx_user_wallet SET usdt_available=usdt_available+#{available},cregis_risk_held=cregis_risk_held+#{risk},"
        +"cumulative_deposit_usdt=cumulative_deposit_usdt+#{cumulative},version=version+1 WHERE user_id=#{user} AND is_deleted=0 "
        +"AND version=#{version} AND usdt_available+#{available}>=0 AND cregis_risk_held+#{risk}>=0 AND cumulative_deposit_usdt+#{cumulative}>=0")
    int walletChange(@Param("user") long user,@Param("version") long version,@Param("available") BigDecimal available,
        @Param("risk") BigDecimal risk,@Param("cumulative") BigDecimal cumulative);
    @Update("UPDATE nx_cregis_reorg_resolution SET status=#{status},held_amount=#{held},recovered_amount=#{recovered},"
        +"outstanding_debt=#{debt},version=version+1,updated_at=NOW(6) WHERE incident_id=#{id} AND version=#{version}")
    int update(@Param("id") long id,@Param("version") long version,@Param("status") String status,
        @Param("held") BigDecimal held,@Param("recovered") BigDecimal recovered,@Param("debt") BigDecimal debt);
    @Select("SELECT id,event_id eventId,project_id projectId,cid,user_id userId,held_amount heldAmount FROM nx_cregis_deposit_incident WHERE event_id=#{event} AND kind=#{kind} ORDER BY id DESC LIMIT 1")
    Map<String,Object> legacyIncident(@Param("event") long event,@Param("kind") String kind);
    @Insert("INSERT INTO nx_cregis_reorg_resolution(incident_id,event_id,project_id,cid,user_id,net_amount,held_amount) "
        +"SELECT i.id,i.event_id,i.project_id,i.cid,i.user_id,e.net_amount,i.held_amount FROM nx_cregis_deposit_incident i "
        +"JOIN nx_cregis_deposit_event e ON e.id=i.event_id WHERE i.id=#{id} AND e.net_amount>0")
    int register(long id);
    @Select("SELECT state FROM nx_cregis_provision_gate WHERE id=1 FOR UPDATE")
    String lockGate();
    @Update("UPDATE nx_cregis_deposit_event SET status=#{status},block_number=#{block},block_hash=#{hash},confirmations=#{confirmations},log_index=#{logIndex},"
        +"canonical_until=CASE WHEN #{status}='CREDITED' THEN #{block}+100 ELSE canonical_until END,"
        +"last_canonical_checked_block=CASE WHEN #{status}='CREDITED' THEN #{block} ELSE last_canonical_checked_block END,"
        +"last_canonical_checked_hash=CASE WHEN #{status}='CREDITED' THEN #{hash} ELSE last_canonical_checked_hash END "
        +"WHERE id=#{event} AND status=#{expected}")
    int eventStatus(@Param("event") long event,@Param("expected") String expected,@Param("status") String status,
        @Param("block") long block,@Param("hash") String hash,@Param("confirmations") int confirmations,@Param("logIndex") int logIndex);
    @Update("UPDATE nx_deposit_order SET status=#{status},updated_at=NOW() WHERE deposit_no=#{no} AND user_id=#{user} AND ledger_id=#{ledger}")
    int depositStatus(@Param("no") String no,@Param("user") long user,@Param("ledger") long ledger,@Param("status") String status);
    @Insert("INSERT INTO nx_cregis_reorg_proposal(incident_id,action,expected_version,evidence_hash,reason,maker_id) "
        +"VALUES(#{incident},#{action},#{version},#{hash},#{reason},#{maker})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int propose(Map<String,Object> request);
    @Select("SELECT id,incident_id incidentId,action,expected_version expectedVersion,evidence_hash evidenceHash,reason,maker_id makerId,"
        +"checker_id checkerId,status,version FROM nx_cregis_reorg_proposal WHERE id=#{id}")
    Map<String,Object> proposal(long id);
    @Select("SELECT id,incident_id incidentId,action,expected_version expectedVersion,evidence_hash evidenceHash,reason,maker_id makerId,status,version "
        +"FROM nx_cregis_reorg_proposal WHERE id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockProposal(long id);
    @Select("SELECT p.id,p.incident_id incidentId,p.action,p.maker_id makerId,p.checker_id checkerId,p.status,p.version,p.evidence_hash evidenceHash,p.reason "
        +"FROM nx_cregis_reorg_proposal p JOIN nx_cregis_reorg_resolution r ON r.incident_id=p.incident_id "
        +"WHERE r.project_id=#{project} AND (#{before}=0 OR p.id<#{before}) ORDER BY p.id DESC LIMIT #{limit}")
    List<Map<String,Object>> proposals(@Param("project") long project,@Param("before") long before,@Param("limit") int limit);
    @Update("UPDATE nx_cregis_reorg_proposal SET status=#{status},checker_id=#{checker},version=version+1,updated_at=NOW(6) "
        +"WHERE id=#{id} AND version=#{version} AND status='MAKER_DONE'")
    int decide(@Param("id") long id,@Param("version") long version,@Param("checker") long checker,@Param("status") String status);
    @Insert("INSERT INTO nx_cregis_reorg_operation(operation_no,incident_id,resolution_version,phase,original_fact_id,attribution_effect,snapshot_json,proof_json,events_json) "
        +"VALUES(#{no},#{incident},#{version},#{phase},#{fact},#{effect},CAST(#{snapshot} AS JSON),CAST(#{proof} AS JSON),CAST(#{events} AS JSON))")
    int operation(Map<String,Object> operation);
    @Select("SELECT operation_no operationNo,phase,snapshot_json snapshotJson,proof_json proofJson,events_json eventsJson,created_at createdAt "
        +"FROM nx_cregis_reorg_operation WHERE incident_id=#{id} ORDER BY resolution_version")
    List<Map<String,Object>> operations(long id);
    @Select("SELECT COUNT(*) FROM nx_cregis_reorg_resolution WHERE user_id=#{user} AND "
        +"(status='INVESTIGATING' OR outstanding_debt>0 OR held_amount>0)")
    int openForUser(long user);
    @Update("UPDATE nx_user SET status='ACTIVE',c2_freeze_source=NULL,c2_freeze_source_ref=NULL,c2_freeze_reason=NULL,"
        +"c2_freeze_operator=NULL,c2_frozen_at=NULL,updated_at=NOW() WHERE id=#{user} AND status='FROZEN' AND is_deleted=0 "
        +"AND c2_freeze_source='CREGIS_DEPOSIT_INCIDENT' AND c2_freeze_source_ref=#{ref}")
    int unfreeze(@Param("user") long user,@Param("ref") String ref);
}
