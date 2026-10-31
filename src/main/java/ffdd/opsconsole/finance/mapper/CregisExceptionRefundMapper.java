package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisExceptionRefundMapper extends BaseMapper<Object> {
    String COLUMNS = "id,case_no caseNo,project_id projectId,source_type sourceType,source_id sourceId,claimant_user_id claimantUserId,"
            + "txid,log_index logIndex,deposit_cid depositCid,return_address returnAddress,deposit_address depositAddress,"
            + "gross_amount grossAmount,fee_amount feeAmount,net_amount netAmount,fee_policy_version feePolicyVersion,"
            + "ownership_receipt ownershipReceipt,ownership_reference ownershipReference,evidence_hash evidenceHash,source_evidence_hash sourceEvidenceHash,state,version,maker_id makerId,checker_id checkerId,"
            + "reason,provider_cid providerCid,business_key businessKey,dispatch_intent_at dispatchIntentAt,chain_txid chainTxid,"
            + "last_error lastError,created_at createdAt";
    String APPROVED_SOURCE = " FROM nx_cregis_exception_refund r JOIN nx_cregis_exception_source s "
            + "ON s.project_id=r.project_id AND s.chain_id='2510' AND s.txid=r.txid AND s.log_index=r.log_index "
            + "JOIN nx_cregis_ownership_challenge c ON c.challenge_id=r.ownership_receipt "
            + "WHERE r.state='APPROVED' AND r.checker_id IS NOT NULL AND r.checker_id<>r.maker_id "
            + "AND s.state='RESERVED' AND s.operation_kind='REFUND' AND s.operation_ref=r.case_no "
            + "AND s.cid=r.deposit_cid AND s.address=r.deposit_address AND s.gross_amount=r.gross_amount "
            + "AND c.project_id=r.project_id AND c.action='REFUND_ORIGIN' AND c.target_user_id IS NULL "
            + "AND c.consumed_ref=r.ownership_reference AND c.evidence_hash=r.source_evidence_hash "
            + "AND c.consumed_signature IS NOT NULL AND c.signer=r.return_address AND c.consumed_at IS NOT NULL AND c.consumed_at<=c.expires_at "
            + "AND r.net_amount>0 AND r.fee_amount>=0 AND r.gross_amount=r.net_amount+r.fee_amount ";
    String APPROVED_CTE="WITH approved_refund_sources AS (SELECT DISTINCT r.project_id,r.txid,r.log_index,r.deposit_cid,r.deposit_address,r.gross_amount "
            +APPROVED_SOURCE+"AND r.project_id=#{project}) ";

    @Select("SELECT mode,fee_usdt feeUsdt,version FROM nx_cregis_exception_refund_policy WHERE project_id=#{project}")
    Map<String,Object> policy(long project);
    @Insert("INSERT IGNORE INTO nx_cregis_exception_refund_policy(project_id) VALUES(#{project})") int ensurePolicy(long project);
    @Select("SELECT mode,fee_usdt feeUsdt,version FROM nx_cregis_exception_refund_policy WHERE project_id=#{project} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockPolicy(long project);
    @Insert("INSERT INTO nx_cregis_exception_refund_policy_case(project_id,expected_policy_version,mode,fee_usdt,maker_id,state,reason) VALUES(#{project},#{version},#{mode},#{fee},#{actor},'MAKER_DONE',#{reason})")
    int proposePolicy(@Param("project") long project,@Param("version") long version,@Param("mode") String mode,
                      @Param("fee") java.math.BigDecimal fee,@Param("actor") long actor,@Param("reason") String reason);
    @Select("SELECT id,project_id projectId,expected_policy_version expectedPolicyVersion,mode,fee_usdt feeUsdt,maker_id makerId,checker_id checkerId,state,version,reason,created_at createdAt FROM nx_cregis_exception_refund_policy_case WHERE project_id=#{project} ORDER BY id DESC LIMIT 50")
    List<Map<String,Object>> policyCases(long project);
    @Select("SELECT id,project_id projectId,expected_policy_version expectedPolicyVersion,mode,fee_usdt feeUsdt,maker_id makerId,state,version FROM nx_cregis_exception_refund_policy_case WHERE id=#{id} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockPolicyCase(long id);
    @Update("UPDATE nx_cregis_exception_refund_policy SET mode=#{mode},fee_usdt=#{fee},version=version+1 WHERE project_id=#{project} AND version=#{version}")
    int applyPolicy(@Param("project") long project,@Param("version") long version,@Param("mode") String mode,@Param("fee") java.math.BigDecimal fee);
    @Update("UPDATE nx_cregis_exception_refund_policy_case SET state=#{state},checker_id=#{actor},version=version+1,checked_at=NOW() WHERE id=#{id} AND version=#{version} AND state='MAKER_DONE'")
    int decidePolicy(@Param("id") long id,@Param("version") long version,@Param("actor") long actor,@Param("state") String state);
    @Select("SELECT LAST_INSERT_ID()") Long lastId();
    @Insert("""
      INSERT INTO nx_cregis_exception_refund(case_no,project_id,source_type,source_id,claimant_user_id,txid,log_index,deposit_cid,
        return_address,deposit_address,gross_amount,fee_amount,net_amount,fee_policy_version,ownership_receipt,evidence_hash,
        source_evidence_hash,ownership_reference,state,maker_id,reason,business_key)
      VALUES(#{caseNo},#{projectId},#{sourceType},#{sourceId},#{claimantUserId},#{txid},#{logIndex},#{depositCid},#{returnAddress},
        #{depositAddress},#{grossAmount},#{feeAmount},#{netAmount},#{feePolicyVersion},#{ownershipReceipt},#{evidenceHash},
        #{sourceEvidenceHash},#{ownershipReference},'MAKER_DONE',#{makerId},#{reason},#{businessKey})
      """) int insertCase(Map<String,Object> row);
    @Select("SELECT "+COLUMNS+" FROM nx_cregis_exception_refund WHERE id=#{id} AND project_id=#{project}")
    Map<String,Object> byId(@Param("project") long project,@Param("id") long id);
    @Select("SELECT "+COLUMNS+" FROM nx_cregis_exception_refund WHERE id=#{id} AND project_id=#{project} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> lockCase(@Param("project") long project,@Param("id") long id);
    @Select("SELECT "+COLUMNS+" FROM nx_cregis_exception_refund WHERE business_key=#{key} AND project_id=#{project}")
    Map<String,Object> byKey(@Param("project") long project,@Param("key") String key);
    @Select("SELECT "+COLUMNS+" FROM nx_cregis_exception_refund WHERE project_id=#{project} AND (#{before}=0 OR id<#{before}) ORDER BY id DESC LIMIT #{limit}")
    List<Map<String,Object>> cases(@Param("project") long project,@Param("before") long before,@Param("limit") int limit);
    @Select("SELECT "+COLUMNS+" FROM nx_cregis_exception_refund WHERE project_id=#{project} AND state IN ('APPROVED','SUBMITTING','UNKNOWN','SENT') AND id>#{after} ORDER BY id LIMIT 50")
    List<Map<String,Object>> work(@Param("project") long project,@Param("after") long after);
    @Update("UPDATE nx_cregis_exception_refund SET state=#{next},version=version+1,checker_id=COALESCE(#{checker},checker_id),last_error=#{error} WHERE id=#{id} AND state=#{previous} AND version=#{version}")
    int state(@Param("id") long id,@Param("version") long version,@Param("previous") String previous,
              @Param("next") String next,@Param("checker") Long checker,@Param("error") String error);
    @Update("UPDATE nx_cregis_exception_refund SET state='SUBMITTING',version=version+1,dispatch_intent_at=NOW(),last_error=NULL WHERE id=#{id} AND state='APPROVED' AND version=#{version} AND dispatch_intent_at IS NULL")
    int intent(@Param("id") long id,@Param("version") long version);
    @Update("UPDATE nx_cregis_exception_refund SET last_error=#{reason},version=version+1 WHERE project_id=#{project} AND id=#{id} AND state='APPROVED' AND dispatch_intent_at IS NULL AND NOT(last_error<=>#{reason})")
    int dispatchBlocked(@Param("project") long project,@Param("id") long id,@Param("reason") String reason);
    @Update("UPDATE nx_cregis_exception_refund SET state='SENT',provider_cid=#{cid},version=version+1,last_error=NULL WHERE id=#{id} AND version=#{version} AND state IN ('SUBMITTING','UNKNOWN') AND (provider_cid IS NULL OR provider_cid=#{cid})")
    int bind(@Param("id") long id,@Param("version") long version,@Param("cid") long cid);
    @Update("UPDATE nx_cregis_exception_refund SET state='UNKNOWN',version=version+1,last_error=#{error} WHERE id=#{id} AND version=#{version} AND state IN ('SUBMITTING','SENT','UNKNOWN')")
    int unknown(@Param("id") long id,@Param("version") long version,@Param("error") String error);
    @Update("UPDATE nx_cregis_exception_refund SET state=#{state},version=version+1,chain_txid=#{txid},last_error=#{error} WHERE id=#{id} AND version=#{version} AND state IN ('SENT','UNKNOWN') AND provider_cid=#{cid}")
    int terminal(@Param("id") long id,@Param("version") long version,@Param("cid") long cid,
                 @Param("state") String state,@Param("txid") String txid,@Param("error") String error);
    @Insert("INSERT INTO nx_cregis_exception_refund_ledger(case_no,event_type,provider_cid,amount_usdt,txid,evidence_json) VALUES(#{caseNo},#{type},#{cid},#{amount},#{txid},#{evidence})")
    int ledger(@Param("caseNo") String caseNo,@Param("type") String type,@Param("cid") Long cid,
               @Param("amount") java.math.BigDecimal amount,@Param("txid") String txid,@Param("evidence") String evidence);
    @Select("SELECT COUNT(*) FROM nx_cregis_risk_alert a WHERE a.project_id=#{project} AND a.severity='P0' AND a.resolved_at IS NULL "
            + "AND NOT(a.kind='UNRESOLVED_EXPOSURE' AND a.alert_key='unresolved-exposure-500') "
            + "AND NOT(a.kind='PROVIDER_ORPHAN' AND (a.alert_key=CONCAT('orphan-cid-',#{cid}) OR EXISTS(SELECT 1 "+APPROVED_SOURCE
            + "AND r.project_id=a.project_id AND a.alert_key=CONCAT('orphan-cid-',r.deposit_cid))))")
    int otherCriticalAlerts(@Param("project") long project,@Param("cid") long cid);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_event WHERE project_id=#{project} AND status IN ('REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')")
    int criticalDeposits(long project);
    @Select(APPROVED_CTE+"SELECT (SELECT COUNT(*) FROM nx_cregis_chain_observation o WHERE o.project_id=#{project} "
            + "AND o.status IN ('UNATTRIBUTED_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_MISSING') "
            + "AND NOT(o.chain_id='2510' AND o.txid<=>#{txid} AND o.log_index<=>#{index}) "
            + "AND NOT(o.chain_id='2510' AND o.status='UNATTRIBUTED_HOLD' AND EXISTS(SELECT 1 FROM approved_refund_sources r WHERE r.project_id=o.project_id AND r.txid=o.txid AND r.log_index=o.log_index))) "
            + "+ (SELECT COUNT(*) FROM nx_cregis_deposit_address a WHERE a.project_id=#{project} "
            + "AND a.state IN ('UNKNOWN','PROVISIONING','VERIFYING','UNATTRIBUTED_HOLD','RETIRED_HOLD') "
            + "AND NOT(a.chain_id='2510' AND a.address<=>#{address} AND a.state IN ('UNATTRIBUTED_HOLD','RETIRED_HOLD')) "
            + "AND NOT(a.chain_id='2510' AND a.state IN ('UNATTRIBUTED_HOLD','RETIRED_HOLD') AND EXISTS(SELECT 1 FROM approved_refund_sources r WHERE r.project_id=a.project_id AND r.deposit_address=a.address))) "
            + "+ (SELECT COUNT(*) FROM nx_cregis_deposit_delivery d WHERE d.accepted=1 AND d.processed_at IS NULL "
            + "AND NOT(d.chain_id='2510' AND d.cid<=>#{cid} AND d.txid<=>#{txid} AND d.address<=>#{address} AND d.gross_amount<=>#{amount}) "
            + "AND NOT(d.chain_id='2510' AND COALESCE(d.signature_valid,0)=1 AND COALESCE(d.timestamp_valid,0)=1 AND COALESCE(d.ip_valid,0)=1 AND EXISTS(SELECT 1 FROM approved_refund_sources r "
            + "WHERE r.project_id=#{project} AND r.deposit_cid=d.cid AND r.txid=d.txid AND r.deposit_address=d.address AND r.gross_amount=d.gross_amount)))")
    int otherUnresolved(@Param("project") long project,@Param("cid") long cid,@Param("txid") String txid,
                        @Param("index") int index,@Param("address") String address,@Param("amount") java.math.BigDecimal amount);
    @Select("SELECT COUNT(*) FROM nx_cregis_exception_refund WHERE project_id=#{project} AND id<>#{id} AND state IN ('SUBMITTING','UNKNOWN','SENT')")
    int otherActiveRefunds(@Param("project") long project,@Param("id") long id);
    @Insert("INSERT IGNORE INTO nx_cregis_exception_refund_callback(payload_hash,case_no,provider_cid,provider_status,txid) VALUES(#{hash},#{caseNo},#{cid},#{status},#{txid})")
    int callback(@Param("hash") String hash,@Param("caseNo") String caseNo,@Param("cid") long cid,@Param("status") int status,@Param("txid") String txid);
    @Select("SELECT COUNT(*) FROM nx_cregis_exception_refund_callback WHERE case_no=#{caseNo} AND provider_status=6")
    int successCallback(String caseNo);
    @Select("SELECT DISTINCT provider_cid FROM nx_cregis_exception_refund_callback WHERE case_no=#{caseNo} LIMIT 2")
    List<Long> callbackCids(String caseNo);
    @Insert("INSERT IGNORE INTO nx_cregis_exception_refund_ledger(case_no,event_type,provider_cid,amount_usdt,txid,evidence_json) VALUES(#{caseNo},'PROVIDER_SUCCESS',#{cid},#{amount},#{txid},'{}')")
    int successProgress(@Param("caseNo") String caseNo,@Param("cid") long cid,@Param("amount") java.math.BigDecimal amount,@Param("txid") String txid);
    @Select("SELECT COUNT(*) FROM nx_cregis_exception_refund_ledger WHERE case_no=#{caseNo} AND event_type='PROVIDER_SUCCESS'")
    int successProgressCount(String caseNo);
    @Update("UPDATE nx_cregis_exception_refund SET state='UNKNOWN',version=version+1,last_error='CREGIS_LATE_PROVIDER_EVIDENCE' WHERE case_no=#{caseNo} AND state IN ('FAILED_NO_TRANSFER','REPROPOSED')")
    int lateEvidence(String caseNo);
    @Select("SELECT state,operation_kind kind,operation_ref ownerCase FROM nx_cregis_exception_source WHERE project_id=#{project} AND chain_id='2510' AND txid=#{txid} AND log_index=#{index} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> guard(@Param("project")long project,@Param("txid")String txid,@Param("index")int index);
}
