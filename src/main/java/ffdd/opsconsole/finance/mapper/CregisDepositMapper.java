package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.CregisDepositEventEntity;
import ffdd.opsconsole.finance.cregis.CregisNetwork;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CregisDepositMapper extends BaseMapper<CregisDepositEventEntity> {
    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('nx_cregis_deposit_event','nx_cregis_chain_observation','nx_cregis_deposit_delivery','nx_cregis_exception_source','nx_cregis_reconcile_run','nx_purchase_payment_arrival') AND COLUMN_NAME='chain_id'")
    int networkSchemaColumnCount();
    @Select("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND (TABLE_NAME='nx_cregis_deposit_event' AND INDEX_NAME='uk_cregis_event_log' AND COLUMN_NAME='chain_id' OR TABLE_NAME='nx_cregis_chain_observation' AND INDEX_NAME='uk_cregis_observation_log' AND COLUMN_NAME='chain_id' OR TABLE_NAME='nx_cregis_exception_source' AND INDEX_NAME='PRIMARY' AND COLUMN_NAME='chain_id' OR TABLE_NAME='nx_deposit_order' AND INDEX_NAME='uk_deposit_chain_tx_asset_log' AND COLUMN_NAME='chain_name' OR TABLE_NAME='nx_purchase_payment_intent' AND INDEX_NAME='uk_purchase_address' AND COLUMN_NAME='payment_method' OR TABLE_NAME='nx_purchase_payment_arrival' AND INDEX_NAME='uk_purchase_arrival' AND COLUMN_NAME='chain_id') AND NON_UNIQUE=0")
    int networkUniqueIndexCount();
    @Select("SELECT COUNT(*) FROM nx_cregis_address_pool_policy WHERE id=2")
    int networkPoolStateCount();
    @Select("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('nx_cregis_deposit_policy','nx_cregis_deposit_policy_snapshot','nx_cregis_deposit_policy_lock','nx_cregis_deposit_policy_install')")
    int policySchemaTableCount();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_policy_install WHERE id=1")
    int depositPolicyInstallCount();
    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND (TABLE_NAME='nx_cregis_deposit_policy_snapshot' AND COLUMN_NAME IN ('project_id','chain_id','token_id','txid','policy_version','required_confirmations') OR TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME IN ('policy_version','policy_fee_usdt'))")
    int policySchemaColumnCount();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_policy")
    int depositPolicyCount();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_policy_lock WHERE id=1")
    int depositPolicyLockCount();
    @Select("""
        SELECT (SELECT COUNT(*) FROM nx_cregis_deposit_event e WHERE NOT EXISTS
          (SELECT 1 FROM nx_cregis_deposit_policy_snapshot s WHERE s.project_id=e.project_id AND s.txid=CASE WHEN LOWER(e.txid) LIKE '0x%' THEN LOWER(e.txid) ELSE CONCAT('0x',LOWER(e.txid)) END AND s.chain_id=CASE e.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE e.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END))
        + (SELECT COUNT(*) FROM nx_cregis_chain_observation o WHERE NOT EXISTS
          (SELECT 1 FROM nx_cregis_deposit_policy_snapshot s WHERE s.project_id=o.project_id AND s.txid=CASE WHEN LOWER(o.txid) LIKE '0x%' THEN LOWER(o.txid) ELSE CONCAT('0x',LOWER(o.txid)) END AND s.chain_id=CASE o.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE o.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END))
        + (SELECT COUNT(*) FROM nx_cregis_deposit_delivery d WHERE d.accepted=1 AND
          (JSON_VALID(d.raw_json)=0 OR d.txid IS NULL OR NOT EXISTS
           (SELECT 1 FROM nx_cregis_deposit_policy_snapshot s WHERE s.project_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(d.raw_json,'$.pid')) AS UNSIGNED) AND s.txid=CASE WHEN LOWER(d.txid) LIKE '0x%' THEN LOWER(d.txid) ELSE CONCAT('0x',LOWER(d.txid)) END AND s.chain_id=CASE d.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE d.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END)))
        + (SELECT COUNT(*) FROM nx_cregis_deposit_policy_snapshot s LEFT JOIN nx_cregis_deposit_policy p ON p.version=s.policy_version WHERE p.version IS NULL OR s.required_confirmations<15)
        """)
    long incompletePolicySnapshotCount();
    @Select("SELECT COUNT(*) FROM nx_cregis_risk_alert WHERE project_id=#{projectId} AND kind='AUTO_FORWARD_UNKNOWN' AND resolved_at IS NULL")
    int openAutoForwardUnknownAlertCount(long projectId);
    @Select("""
            SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
             AND TABLE_NAME IN ('nx_cregis_deposit_address','nx_cregis_deposit_delivery',
               'nx_cregis_deposit_event','nx_cregis_chain_observation','nx_cregis_chain_cursor',
               'nx_cregis_provision_gate','nx_cregis_deposit_incident','nx_cregis_address_pool_policy')
            """)
    int schemaTableCount();

    @Select("""
            SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME IN ('nx_cregis_reconcile_watermark','nx_cregis_reconcile_run',
                                 'nx_cregis_risk_alert','nx_cregis_review_case','nx_cregis_switch_case')
            """)
    int controlTableCount();
    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_cregis_provision_gate'
              AND COLUMN_NAME IN ('assign_enabled','credit_enabled','payout_enabled','version','switch_reason')
            """)
    int controlColumnCount();
    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_delivery'
               AND COLUMN_NAME IN ('source_ip','signature_valid','timestamp_valid','ip_valid')
            """)
    int deliveryEvidenceColumnCount();

    @Select("""
            SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
             AND (TABLE_NAME='nx_cregis_deposit_address' AND INDEX_NAME IN
                    ('uk_cregis_address_user','uk_cregis_address_value')
               OR TABLE_NAME='nx_cregis_deposit_event' AND INDEX_NAME IN
                    ('uk_cregis_event_log','uk_cregis_event_cid'))
             AND SEQ_IN_INDEX=1 AND NON_UNIQUE=0
            """)
    int schemaUniqueIndexCount();

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
             AND TABLE_NAME='nx_cregis_deposit_address'
             AND ((COLUMN_NAME='user_id' AND IS_NULLABLE='YES') OR COLUMN_NAME IN ('creation_block','verification_block'))
            """)
    int schemaPoolColumnCount();
    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND (TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='cregis_risk_held'
                OR TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME IN
                   ('canonical_until','last_canonical_checked_block','last_canonical_checked_hash'))
            """)
    int schemaRiskColumnCount();
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_deposit_address
             WHERE state IN ('VERIFYING','UNASSIGNED','READY','UNATTRIBUTED_HOLD')
               AND (creation_block IS NULL OR (state='READY' AND
                   (allocation_block IS NULL OR allocation_hash IS NULL)))
            """)
    int legacyAddressCount();
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_deposit_event
             WHERE status='CREDITED' AND (canonical_until IS NULL
                   OR last_canonical_checked_block IS NULL OR last_canonical_checked_hash IS NULL)
            """)
    int legacyCreditedEventCount();

    @Select("""
            SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_deposit_order' AND INDEX_NAME='uk_deposit_chain_tx_asset_log'
              AND SEQ_IN_INDEX=1 AND NON_UNIQUE=0
            """)
    int depositOrderLogIndexCount();

    @Update("""
            UPDATE nx_cregis_provision_gate SET state='BUSY'
             WHERE id=1 AND state='IDLE' AND assign_enabled=1
            """)
    int claimProvisionGate();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId}")
    int allocatedAddressCount(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='UNASSIGNED' AND user_id IS NULL")
    int availableAddressCount(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Select("SELECT p.enabled,p.target_count AS target,p.low_watermark AS lowWatermark,p.batch_size AS batchSize,p.max_concurrency AS concurrency,r.refill_active AS refillActive,UNIX_TIMESTAMP(r.retry_after)*1000 AS retryAfter,r.last_error AS lastError,p.version FROM nx_cregis_address_pool_policy p JOIN nx_cregis_address_pool_policy r ON r.id=#{network.cursorId,javaType=int} WHERE p.id=1")
    Map<String,Object> poolPolicy(@Param("network") CregisNetwork network);
    @Update("UPDATE nx_cregis_address_pool_policy SET enabled=#{enabled},target_count=#{target},low_watermark=#{low},batch_size=#{batch},max_concurrency=#{concurrency},retry_after=NULL,last_error=NULL,version=version+1 WHERE id=1 AND version=#{version}")
    int updatePoolPolicy(@Param("enabled") boolean enabled,@Param("target") int target,@Param("low") int low,
            @Param("batch") int batch,@Param("concurrency") int concurrency,@Param("version") long version);
    @Update("UPDATE nx_cregis_address_pool_policy SET refill_active=#{active} WHERE id=#{network.cursorId,javaType=int}")
    int setPoolRefillActive(@Param("network") CregisNetwork network,boolean active);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state IN ('QUEUED','PROVISIONING','VERIFYING','UNKNOWN')")
    int outstandingAddressCount(@Param("projectId") long projectId,@Param("chainId") String chainId);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='PROVISIONING'")
    int submittingAddressCount(@Param("projectId") long projectId,@Param("chainId") String chainId);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='QUEUED'")
    int queuedAddressCount(@Param("projectId") long projectId,@Param("chainId") String chainId);
    @Insert("INSERT INTO nx_cregis_deposit_address(user_id,project_id,chain_id,request_id,state) VALUES(NULL,#{projectId},#{chainId},#{requestId},'QUEUED')")
    int enqueuePoolAddress(@Param("projectId") long projectId,@Param("chainId") String chainId,@Param("requestId") String requestId);
    @Select("SELECT id,request_id AS requestId FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Map<String,Object> lockQueuedAddress(@Param("projectId") long projectId,@Param("chainId") String chainId);
    @Update("UPDATE nx_cregis_deposit_address SET state='PROVISIONING',creation_block=#{block},updated_at=NOW() WHERE id=#{id} AND state='QUEUED'")
    int claimQueuedAddress(@Param("id") long id,@Param("block") long block);
    @Update("UPDATE nx_cregis_deposit_address SET state='CANCELLED' WHERE id=#{id} AND state='QUEUED'")
    int cancelQueuedAddress(long id);
    @Update("UPDATE nx_cregis_deposit_address SET state='REJECTED' WHERE request_id=#{requestId} AND state='PROVISIONING'")
    int rejectPoolAttempt(String requestId);
    @Update("UPDATE nx_cregis_address_pool_policy SET retry_after=DATE_ADD(NOW(),INTERVAL #{seconds} SECOND),last_error=#{code} WHERE id=#{network.cursorId,javaType=int}")
    int pausePoolSubmission(@Param("network") CregisNetwork network,@Param("code") String code,@Param("seconds") int seconds);
    @Select("SELECT id,address,creation_block AS creationBlock,COALESCE(verification_block,creation_block) AS verificationBlock FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='VERIFYING' ORDER BY id LIMIT #{limit}")
    List<Map<String,Object>> verifyingCandidates(@Param("projectId") long projectId,@Param("chainId") String chainId,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state IN ('PROVISIONING','VERIFYING','UNKNOWN')")
    int pendingAddressCount(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='UNKNOWN'")
    int unknownAddressCount(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Update("UPDATE nx_cregis_deposit_address SET state='UNKNOWN' WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='PROVISIONING' AND updated_at < DATE_SUB(NOW(),INTERVAL 2 MINUTE)")
    int expireProvisioning(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(state='UNASSIGNED'),0) AS unassigned,
                   COALESCE(SUM(state='READY'),0) AS assigned,
                   COALESCE(SUM(state IN ('VERIFYING','PROVISIONING')),0) AS verifying,
                   COALESCE(SUM(state='QUEUED'),0) AS queued,
                   COALESCE(SUM(state='UNKNOWN'),0) AS `unknown`
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId}
            """)
    Map<String, Object> addressPool(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Update("UPDATE nx_cregis_provision_gate SET state='IDLE' WHERE id=1 AND state='BUSY'")
    int releaseProvisionGate();
    @Update("""
            UPDATE nx_cregis_provision_gate
               SET state='BLOCKED',assign_enabled=0,credit_enabled=0,payout_enabled=0,
                   version=version+1,switch_reason='CREGIS_PROVISION_UNKNOWN'
             WHERE id=1 AND state='BUSY'
            """)
    int blockProvisionGate();
    @Update("""
            UPDATE nx_cregis_provision_gate
               SET state='BLOCKED',assign_enabled=0,credit_enabled=0,payout_enabled=0,
                   version=version+1,switch_reason='CREGIS_DEPOSIT_INCIDENT'
             WHERE id=1 AND (state<>'BLOCKED' OR assign_enabled<>0 OR credit_enabled<>0 OR payout_enabled<>0)
            """)
    int blockProvisionGateAny();

    @Insert("""
            INSERT INTO nx_cregis_deposit_address
            (user_id,project_id,chain_id,request_id,state,creation_block)
            VALUES (NULL,#{projectId},#{chainId},#{requestId},'PROVISIONING',#{creationBlock})
            """)
    int insertPoolAttempt(@Param("projectId") long projectId, @Param("chainId") String chainId,
                          @Param("requestId") String requestId, @Param("creationBlock") long creationBlock);
    @Update("""
            UPDATE nx_cregis_deposit_address SET address=#{address},state='VERIFYING'
             WHERE request_id=#{requestId} AND state='PROVISIONING' AND user_id IS NULL
            """)
    int recordPoolCandidate(@Param("requestId") String requestId, @Param("address") String address);
    @Update("""
            UPDATE nx_cregis_deposit_address SET state='UNKNOWN'
             WHERE request_id=#{requestId} AND state='PROVISIONING'
            """)
    int unknownPoolAttempt(@Param("requestId") String requestId);
    @Select("""
            SELECT id,address,creation_block AS creationBlock,
                   COALESCE(verification_block,creation_block) AS verificationBlock FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='VERIFYING'
             ORDER BY id LIMIT 1
            """)
    Map<String, Object> verifyingCandidate(@Param("projectId") long projectId,
                                            @Param("chainId") String chainId);
    @Update("""
            UPDATE nx_cregis_deposit_address SET verification_block=#{next}
             WHERE id=#{id} AND state='VERIFYING' AND user_id IS NULL
               AND COALESCE(verification_block,creation_block)=#{expected}
            """)
    int advanceCandidateVerification(@Param("id") long id, @Param("expected") long expected, @Param("next") long next);
    @Update("""
            UPDATE nx_cregis_deposit_address SET state='UNASSIGNED'
             WHERE id=#{id} AND state='VERIFYING' AND user_id IS NULL
            """)
    int readyPoolCandidate(@Param("id") long id);
    @Update("""
            UPDATE nx_cregis_deposit_address SET state=CASE WHEN state='RETIRED' THEN 'RETIRED_HOLD' ELSE 'UNATTRIBUTED_HOLD' END
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND address=#{address}
               AND state IN ('VERIFYING','UNASSIGNED','RETIRED') AND user_id IS NULL
            """)
    int holdPoolAddress(@Param("projectId") long projectId, @Param("chainId") String chainId,
                        @Param("address") String address);
    @Select("""
            SELECT id,address FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId}
               AND state='UNASSIGNED' AND user_id IS NULL
             ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Map<String, Object> lockUnassignedAddress(@Param("projectId") long projectId,
                                               @Param("chainId") String chainId);
    @Select("SELECT id,address FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='UNASSIGNED' AND user_id IS NULL ORDER BY id LIMIT #{offset},1")
    Map<String,Object> peekUnassignedAddress(@Param("projectId") long projectId,@Param("chainId") String chainId,@Param("offset") int offset);
    @Select("SELECT id,address FROM nx_cregis_deposit_address WHERE id=#{id} AND project_id=#{projectId} AND chain_id=#{chainId} AND state='UNASSIGNED' AND user_id IS NULL FOR UPDATE SKIP LOCKED")
    Map<String,Object> lockPoolCandidate(@Param("projectId") long projectId,@Param("chainId") String chainId,@Param("id") long id);
    @Update("""
            UPDATE nx_cregis_deposit_address
               SET user_id=#{userId},state='READY',allocation_block=#{block},allocation_hash=#{hash}
             WHERE id=#{id} AND state='UNASSIGNED' AND user_id IS NULL
            """)
    int assignPoolAddress(@Param("id") long id, @Param("userId") long userId,
                          @Param("block") long block, @Param("hash") String hash);
    @Select("SELECT next_block FROM nx_cregis_chain_cursor WHERE id=#{network.cursorId,javaType=int} FOR UPDATE")
    Long lockCursor(@Param("network") CregisNetwork network);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND address=#{address} AND block_number<=#{block}
            """)
    int addressObservationCount(@Param("network") CregisNetwork network,@Param("projectId") long projectId,
                                @Param("address") String address, @Param("block") long block);

    @Select("""
            SELECT state,assign_enabled AS assignEnabled,credit_enabled AS creditEnabled,
                   payout_enabled AS payoutEnabled,version,switch_reason AS switchReason,
                   updated_at AS updatedAt FROM nx_cregis_provision_gate WHERE id=1
            """)
    Map<String, Object> provisionGate();
    @Select("""
            SELECT state,assign_enabled AS assignEnabled,credit_enabled AS creditEnabled,
                   payout_enabled AS payoutEnabled,version,switch_reason AS switchReason,
                   updated_at AS updatedAt FROM nx_cregis_provision_gate WHERE id=1 FOR UPDATE
            """)
    Map<String, Object> lockProvisionGate();
    @Select("""
            WITH delivery_json AS (
              SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
                FROM nx_cregis_deposit_delivery d
               WHERE accepted=1 AND (processed_at IS NULL OR reason IN
                 ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
            ), delivery_snapshots AS (
              SELECT d.*,
                     CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
                          THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
                     CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
                          AND JSON_VALID(raw_json)
                          AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(#{projectId} AS CHAR)
                          AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))=d.chain_id AND d.chain_id IN ('2510','60')
                          AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))=CASE d.chain_id WHEN '60' THEN '0xdac17f958d2ee523a2206206994597c13d831ec7' ELSE '0x55d398326f99059ff775485246999027b3197955' END
                          AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                            ('USDT',CASE d.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END,CASE d.chain_id WHEN '60' THEN '60@0XDAC17F958D2EE523A2206206994597C13D831EC7' ELSE '2510@0X55D398326F99059FF775485246999027B3197955' END)
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
                          AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
                          AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
                          AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
                          AND gross_amount>0
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
                          THEN 1 ELSE 0 END AS snapshot_valid
                FROM delivery_json d
            ), delivery_exposure AS (
              SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
                FROM delivery_snapshots d
               WHERE snapshot_valid=0 OR (
                 NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                             WHERE e.project_id=#{projectId} AND e.chain_id=d.chain_id AND e.cid=d.cid
                               AND e.txid=d.txid AND e.address=d.address
                               AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                                 WHERE o.project_id=#{projectId} AND o.chain_id=d.chain_id AND o.txid=d.txid AND o.address=d.address
                                   AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                                   AND o.block_number=d.snapshot_block))
               GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
                        cid,txid,address,gross_amount,snapshot_block
            )
            SELECT
              COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
                         WHERE project_id=#{projectId} AND status IN
                         ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
              + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                            FROM nx_cregis_chain_observation o
                           WHERE o.project_id=#{projectId} AND o.status NOT IN ('MATCHED','REFUNDED')
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                             WHERE e.project_id=o.project_id AND e.chain_id=o.chain_id
                                               AND e.txid=o.txid AND e.log_index=o.log_index)),0)
              + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
                   FROM delivery_exposure)
              + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
                           WHERE w.asset='USDT' AND w.is_deleted=0
                             AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
            """)
    BigDecimal unresolvedExposure(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_provision_gate
               SET assign_enabled=0,credit_enabled=0,payout_enabled=0,
                   state='BLOCKED',switch_reason=#{reason},version=version+1
             WHERE id=1 AND (assign_enabled<>0 OR credit_enabled<>0 OR payout_enabled<>0 OR state<>'BLOCKED')
            """)
    int tripAll(@Param("reason") String reason);
    @Update("""
            UPDATE nx_cregis_provision_gate
               SET assign_enabled=0,credit_enabled=0,payout_enabled=0,
                   state='BLOCKED',switch_reason=#{reason},version=version+1
             WHERE id=1 AND version=#{expectedVersion}
            """)
    int forceEmergencyOff(@Param("reason") String reason,
                          @Param("expectedVersion") long expectedVersion);
    @Update("""
            UPDATE nx_cregis_provision_gate
               SET assign_enabled=#{assign},credit_enabled=#{credit},payout_enabled=#{payout},
                   state=IF(#{assign}=1 OR #{credit}=1 OR #{payout}=1,'IDLE','BLOCKED'),
                   switch_reason=#{reason},version=version+1
             WHERE id=1 AND version=#{expectedVersion}
            """)
    int setSwitches(@Param("assign") int assign, @Param("credit") int credit,
                    @Param("payout") int payout, @Param("reason") String reason,
                    @Param("expectedVersion") long expectedVersion);
    @Insert("""
            INSERT IGNORE INTO nx_cregis_risk_alert(project_id,alert_key,severity,kind,evidence)
            VALUES(#{projectId},#{key},#{severity},#{kind},#{evidence})
            """)
    int insertRiskAlert(@Param("projectId") long projectId, @Param("key") String key,
                        @Param("severity") String severity, @Param("kind") String kind,
                        @Param("evidence") String evidence);
    @Select("""
            SELECT id,alert_key AS alertKey,severity,kind,evidence,created_at AS createdAt
              FROM nx_cregis_risk_alert WHERE project_id=#{projectId}
               AND severity IN ('P0','P1') AND resolved_at IS NULL
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> riskAlerts(@Param("projectId") long projectId);
    @Select("""
            SELECT id,alert_key AS alertKey,severity,kind,evidence,created_at AS createdAt
              FROM nx_cregis_risk_alert WHERE project_id=#{projectId}
               AND severity IN ('P0','P1') AND resolved_at IS NULL AND id<#{beforeId}
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> riskAlertsBefore(@Param("projectId") long projectId,
                                                @Param("beforeId") long beforeId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_risk_alert WHERE project_id=#{projectId}
              AND severity IN ('P0','P1') AND resolved_at IS NULL
            """)
    int openRiskAlertCount(@Param("projectId") long projectId);
    @Select("""
            SELECT TIMESTAMPDIFF(SECOND,created_at,NOW()) FROM nx_cregis_risk_alert
             WHERE project_id=#{projectId} AND alert_key=#{key}
            """)
    Long riskAlertAgeSeconds(@Param("projectId") long projectId, @Param("key") String key);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_risk_alert
             WHERE project_id=#{projectId} AND severity='P0' AND resolved_at IS NULL
            """)
    int openCriticalAlertCount(@Param("projectId") long projectId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_risk_alert
             WHERE project_id=#{projectId} AND severity='P0'
               AND kind<>'UNRESOLVED_EXPOSURE' AND resolved_at IS NULL
            """)
    int openNonExposureCriticalAlertCount(@Param("projectId") long projectId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_risk_alert
             WHERE project_id=#{projectId} AND severity='P0'
               AND kind<>'MANUAL_EMERGENCY_OFF' AND resolved_at IS NULL
            """)
    int openCriticalAlertCountExceptManual(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_risk_alert SET resolved_at=NOW()
             WHERE project_id=#{projectId} AND kind='UNRESOLVED_EXPOSURE'
               AND resolved_at IS NULL
            """)
    int resolveExposureAlert(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_risk_alert SET resolved_at=NULL
             WHERE project_id=#{projectId} AND kind='UNRESOLVED_EXPOSURE'
               AND resolved_at IS NOT NULL
            """)
    int reopenExposureAlert(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_risk_alert SET resolved_at=NOW()
             WHERE project_id=#{projectId} AND kind='MANUAL_EMERGENCY_OFF'
               AND resolved_at IS NULL
            """)
    int resolveManualEmergencyAlerts(@Param("projectId") long projectId);
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveUser(@Param("userId") long userId);
    @Select("""
            SELECT id,chain_id AS chainId,user_id AS userId,address,state,created_at AS createdAt
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND state IN ('UNKNOWN','PROVISIONING','VERIFYING','UNATTRIBUTED_HOLD','RETIRED_HOLD')
             ORDER BY id LIMIT 50
            """)
    List<Map<String, Object>> uncertainAddresses(@Param("projectId") long projectId);
    @Select("""
            SELECT id,chain_id AS chainId,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
                   status,created_at AS createdAt
              FROM nx_cregis_deposit_event
             WHERE project_id=#{projectId} AND status IN
               ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> heldDeposits(@Param("projectId") long projectId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_deposit_event
             WHERE project_id=#{projectId}
               AND status IN ('REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')
            """)
    int criticalHeldDepositCount(@Param("projectId") long projectId);
    @Select("""
            SELECT id,chain_id AS chainId,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   status,last_error AS lastError,checked_at AS checkedAt
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status IN
               ('PROVIDER_MISSING','UNATTRIBUTED_HOLD','CHAIN_MISMATCH_HOLD')
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> providerMissing(@Param("projectId") long projectId);
    @Select("""
            SELECT d.id,d.chain_id AS chainId,d.cid,d.txid,d.address,d.gross_amount AS grossAmount,
                   d.last_error AS lastError,d.created_at AS createdAt
              FROM nx_cregis_deposit_delivery d
             WHERE d.accepted=1 AND d.processed_at IS NULL AND d.last_error IS NOT NULL
             ORDER BY d.id DESC LIMIT 50
            """)
    List<Map<String, Object>> failedDeliveries(@Param("projectId") long projectId);
    @Select("""
            SELECT id,chain_id AS chainId,cid,txid,address,gross_amount AS grossAmount,reason,
                   last_error AS lastError,created_at AS createdAt,
                   TIMESTAMPDIFF(SECOND,created_at,NOW()) AS ageSeconds
              FROM nx_cregis_deposit_delivery
             WHERE accepted=1 AND processed_at IS NULL
             ORDER BY id LIMIT 50
            """)
    List<Map<String, Object>> pendingAcceptedDeliveries();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_delivery WHERE accepted=1 AND processed_at IS NULL AND id<>#{exceptId}")
    int pendingAcceptedDeliveryCount(@Param("exceptId") long exceptId);
    @Select("""
            SELECT raw_json AS rawJson FROM nx_cregis_deposit_delivery
             WHERE accepted=1 AND processed_at IS NULL AND id<>#{exceptId}
             ORDER BY id
            """)
    List<Map<String, Object>> pendingAcceptedDeliveryEvidence(@Param("exceptId") long exceptId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_deposit_delivery
             WHERE cid=#{cid} AND reason IN
               ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD')
            """)
    int disputedDeliveryCount(@Param("cid") long cid);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_deposit_delivery d
             WHERE d.reason IN
               ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD')
            """)
    int disputedDeliveryCountForProject(@Param("projectId") long projectId);

    @Select("""
            SELECT address,state,project_id AS projectId,allocation_block AS allocationBlock
              FROM nx_cregis_deposit_address
             WHERE user_id=#{userId} AND chain_id=#{chainId}
            """)
    List<Map<String, Object>> addressForUser(@Param("userId") long userId, @Param("chainId") String chainId);

    @Insert("""
            INSERT INTO nx_cregis_deposit_address
            (user_id,project_id,chain_id,request_id,state) VALUES
            (#{userId},#{projectId},#{chainId},#{requestId},'PROVISIONING')
            """)
    int insertAddressAttempt(@Param("userId") long userId, @Param("projectId") long projectId,
                             @Param("chainId") String chainId, @Param("requestId") String requestId);

    @Update("""
            UPDATE nx_cregis_deposit_address SET address=#{address}
             WHERE user_id=#{userId} AND request_id=#{requestId} AND state='PROVISIONING'
            """)
    int recordCandidateAddress(@Param("address") String address, @Param("userId") long userId,
                               @Param("requestId") String requestId);

    @Update("""
            UPDATE nx_cregis_deposit_address SET address=#{address},allocation_block=#{block},
              allocation_hash=#{hash},state='READY'
             WHERE user_id=#{userId} AND request_id=#{requestId} AND state='PROVISIONING'
            """)
    int readyAddress(@Param("address") String address, @Param("block") long block,
                     @Param("hash") String hash, @Param("userId") long userId,
                     @Param("requestId") String requestId);

    @Update("""
            UPDATE nx_cregis_deposit_address SET state='UNKNOWN'
             WHERE user_id=#{userId} AND request_id=#{requestId} AND state='PROVISIONING'
            """)
    int unknownAddress(@Param("userId") long userId, @Param("requestId") String requestId);

    @Select("""
            SELECT * FROM (
              SELECT CONCAT('CR-',e.cid) AS depositId,e.txid AS txHash,e.address,CASE e.chain_id WHEN '60' THEN 'ERC20' ELSE 'BEP20' END AS network,
                     e.gross_amount AS grossAmountUsdt,
                      CASE WHEN e.status IN ('CREDITED','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')
                           THEN e.net_amount ELSE 0 END AS creditedUsdt,
                     e.confirmations,e.status,UNIX_TIMESTAMP(e.created_at)*1000 AS createdAt,
                     UNIX_TIMESTAMP(e.credited_at)*1000 AS creditedAt,
                     GREATEST(s.required_confirmations,#{safetyFloor}) AS requiredConfirmations
                FROM nx_cregis_deposit_event e
                LEFT JOIN nx_cregis_deposit_policy_snapshot s ON s.project_id=e.project_id
                 AND s.chain_id=CASE e.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE e.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END
                 AND s.txid=CASE WHEN LOWER(e.txid) LIKE '0x%' THEN LOWER(e.txid) ELSE CONCAT('0x',LOWER(e.txid)) END
               WHERE e.user_id=#{userId} AND e.project_id=#{projectId}
              UNION ALL
              SELECT CONCAT('CR-',d.cid),d.txid,d.address,CASE d.chain_id WHEN '60' THEN 'ERC20' ELSE 'BEP20' END,d.gross_amount,0,0,'CONFIRMING',
                     UNIX_TIMESTAMP(MIN(d.created_at))*1000,NULL,
                     GREATEST(s.required_confirmations,#{safetyFloor})
                FROM nx_cregis_deposit_delivery d
                JOIN nx_cregis_deposit_address a ON a.project_id=#{projectId} AND a.address=d.address AND a.chain_id=d.chain_id
                 AND a.user_id=#{userId} AND a.state='READY'
                LEFT JOIN nx_cregis_deposit_policy_snapshot s ON s.project_id=#{projectId}
                 AND s.chain_id=CASE d.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE d.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END
                 AND s.txid=CASE WHEN LOWER(d.txid) LIKE '0x%' THEN LOWER(d.txid) ELSE CONCAT('0x',LOWER(d.txid)) END
               WHERE d.accepted=1 AND d.processed_at IS NULL AND d.cid IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_event e
                                  WHERE e.project_id=#{projectId} AND e.chain_id=d.chain_id AND e.cid=d.cid)
               GROUP BY d.cid,d.txid,d.address,d.chain_id,d.gross_amount,s.required_confirmations
            ) deposit_rows ORDER BY createdAt DESC LIMIT 50
            """)
    List<Map<String, Object>> deposits(@Param("userId") long userId, @Param("projectId") long projectId,
                                       @Param("safetyFloor") int safetyFloor);

    @Select("""
            SELECT CAST(e.id AS CHAR) AS id,CAST(e.cid AS CHAR) AS cid,CASE e.chain_id WHEN '60' THEN 'ERC20' ELSE 'BEP20' END AS network,
                   CAST(e.user_id AS CHAR) AS userId,e.txid,e.log_index AS logIndex,e.address,
                   CAST(e.gross_amount AS CHAR) AS grossAmount,
                   CAST(e.fee_amount AS CHAR) AS feeAmount,
                   CAST(e.net_amount AS CHAR) AS netAmount,e.confirmations,e.status,
                   e.created_at AS createdAt,e.credited_at AS creditedAt,
                   d.deposit_no AS depositNo
              FROM nx_cregis_deposit_event e
              LEFT JOIN nx_deposit_order d ON d.ledger_id=e.ledger_id AND d.is_deleted=0
               AND d.user_id=e.user_id AND d.deposit_no=CONCAT('CR-',e.cid)
               AND d.chain_name=CASE e.chain_id WHEN '60' THEN 'CREGIS_USDT_ERC20' ELSE 'CREGIS_USDT_BEP20' END AND d.chain_tx_hash=e.txid
               AND d.chain_log_index=e.log_index
             WHERE e.project_id=#{projectId} AND (#{beforeId}=0 OR e.id<#{beforeId})
             ORDER BY e.id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> adminDeposits(@Param("projectId") long projectId,
                                            @Param("beforeId") long beforeId,
                                            @Param("limit") int limit);

    @Insert("""
            INSERT INTO nx_cregis_deposit_delivery
            (chain_id,payload_sha256,raw_json,cid,txid,address,gross_amount,accepted,reason,
             source_ip,signature_valid,timestamp_valid,ip_valid)
            VALUES (CASE WHEN #{accepted}=1 THEN JSON_UNQUOTE(JSON_EXTRACT(#{raw},'$.chain_id')) ELSE '2510' END,#{hash},#{raw},#{cid},#{txid},#{address},#{amount},#{accepted},#{reason},
                    #{sourceIp},#{signatureValid},#{timestampValid},#{ipValid})
            """)
    int insertDelivery(@Param("hash") String hash, @Param("raw") String raw,
                       @Param("cid") Long cid, @Param("txid") String txid,
                       @Param("address") String address, @Param("amount") BigDecimal amount,
                       @Param("accepted") int accepted, @Param("reason") String reason,
                       @Param("sourceIp") String sourceIp, @Param("signatureValid") int signatureValid,
                       @Param("timestampValid") int timestampValid,
                       @Param("ipValid") int ipValid);

    @Select("""
            SELECT id,raw_json AS rawJson FROM nx_cregis_deposit_delivery
             WHERE accepted=1 AND processed_at IS NULL ORDER BY id LIMIT 50
            """)
    List<Map<String, Object>> pendingDeliveries();

    @Update("UPDATE nx_cregis_deposit_delivery SET processed_at=NOW(),reason=#{reason} WHERE id=#{id}")
    int finishDelivery(@Param("id") long id, @Param("reason") String reason);
    @Update("UPDATE nx_cregis_deposit_delivery SET last_error=#{reason} WHERE id=#{id} AND processed_at IS NULL")
    int markDeliveryRetry(@Param("id") long id, @Param("reason") String reason);

    @Select("""
            SELECT user_id AS userId,allocation_block AS allocationBlock,
                   allocation_hash AS allocationHash
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND address=#{address} AND state='READY'
            """)
    List<Map<String, Object>> addressOwner(@Param("projectId") long projectId,
                                           @Param("chainId") String chainId,
                                           @Param("address") String address);

    @Select("""
            SELECT id,address,user_id AS userId,state,creation_block AS creationBlock,
                   allocation_block AS allocationBlock
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId}
               AND state IN ('VERIFYING','UNASSIGNED','READY','UNATTRIBUTED_HOLD','RETIRED','RETIRED_HOLD')
               AND id>#{afterId} AND id<=#{maxId}
             ORDER BY id LIMIT 60
            """)
    List<Map<String, Object>> allocations(@Param("projectId") long projectId,
                                          @Param("chainId") String chainId,
                                          @Param("afterId") long afterId, @Param("maxId") long maxId);

    @Select("""
            SELECT MIN(creation_block) AS firstBlock,MAX(id) AS maxId,
                   COALESCE(SUM(creation_block IS NULL),0) AS missingBlocks
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId}
               AND state IN ('VERIFYING','UNASSIGNED','READY','UNATTRIBUTED_HOLD','RETIRED','RETIRED_HOLD')
            """)
    Map<String,Object> allocationBounds(@Param("projectId") long projectId, @Param("chainId") String chainId);

    @Insert("INSERT IGNORE INTO nx_cregis_chain_cursor(id,next_block) VALUES (#{network.cursorId,javaType=int},#{block})")
    int ensureCursor(@Param("network") CregisNetwork network,@Param("block") long block);
    @Select("SELECT next_block FROM nx_cregis_chain_cursor WHERE id=#{network.cursorId,javaType=int}")
    Long cursor(@Param("network") CregisNetwork network);
    @Update("UPDATE nx_cregis_chain_cursor SET next_block=#{next} WHERE id=#{network.cursorId,javaType=int} AND next_block=#{expected}")
    int advanceCursor(@Param("network") CregisNetwork network,@Param("next") long next, @Param("expected") long expected);

    @Select("""
            SELECT id,address,raw_amount AS rawAmount,block_number AS blockNumber,status
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND txid=#{txid} AND log_index=#{logIndex}
            """)
    Map<String, Object> observationByLog(@Param("network") CregisNetwork network,@Param("projectId") long projectId,
                                         @Param("txid") String txid, @Param("logIndex") int logIndex);
    @Insert("""
            INSERT INTO nx_cregis_chain_observation
            (project_id,chain_id,txid,log_index,address,raw_amount,block_number,status)
            VALUES (#{projectId},#{network.chainId,javaType=java.lang.String},#{txid},#{logIndex},#{address},#{amount},#{blockNumber},#{status})
            """)
    int insertObservation(@Param("network") CregisNetwork network,@Param("projectId") long projectId, @Param("txid") String txid,
                          @Param("logIndex") int logIndex, @Param("address") String address,
                          @Param("amount") String amount, @Param("blockNumber") long blockNumber,
                          @Param("status") String status);

    @Select("""
            SELECT id,chain_id AS chainId,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   block_number AS blockNumber FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status='PROVIDER_MISSING'
             ORDER BY checked_at,id LIMIT 50
            """)
    List<Map<String, Object>> missingObservations(@Param("projectId") long projectId);
    @Select("""
            SELECT id,chain_id AS chainId,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   block_number AS blockNumber,checked_at AS checkedAt
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status='UNATTRIBUTED_HOLD'
             ORDER BY checked_at,id LIMIT 50
            """)
    List<Map<String, Object>> unattributedObservations(@Param("projectId") long projectId);
    @Update("UPDATE nx_cregis_chain_observation SET checked_at=NOW(),last_error=#{reason} WHERE id=#{id}")
    int touchObservation(@Param("id") long id, @Param("reason") String reason);
    @Update("""
            UPDATE nx_cregis_chain_observation SET status='CHAIN_MISMATCH_HOLD',
              checked_at=NOW(),last_error='CREGIS_CHAIN_MISMATCH'
             WHERE id=#{id} AND status='PROVIDER_MISSING'
            """)
    int holdChainMismatch(@Param("id") long id);
    @Update("""
            UPDATE nx_cregis_chain_observation SET status='MATCHED',checked_at=NOW()
             WHERE id=#{id} AND status='PROVIDER_MISSING'
            """)
    int matchObservation(@Param("id") long id);

    @Select("""
            SELECT id,chain_id AS chainId,user_id AS userId,txid,log_index AS logIndex,address,
                   gross_amount AS grossAmount,net_amount AS netAmount,status,
                   block_number AS blockNumber,block_hash AS blockHash,confirmations
              FROM nx_cregis_deposit_event WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND cid=#{cid} FOR UPDATE
            """)
    List<Map<String, Object>> lockEvent(@Param("network") CregisNetwork network,@Param("projectId") long projectId, @Param("cid") long cid);
    @Select("""
            SELECT id,cid,status FROM nx_cregis_deposit_event WHERE id=#{eventId} FOR UPDATE
            """)
    List<Map<String, Object>> lockReviewEvent(@Param("eventId") long eventId);

    @Insert("""
            INSERT INTO nx_cregis_deposit_event
            (user_id,project_id,chain_id,cid,txid,log_index,origin_log_index,address,gross_amount,fee_amount,net_amount,
             block_number,block_hash,confirmations,canonical_until,
             last_canonical_checked_block,last_canonical_checked_hash,status)
            VALUES (#{userId},#{projectId},#{network.chainId,javaType=java.lang.String},#{cid},#{txid},#{logIndex},#{logIndex},#{address},#{amount},#{fee},#{net},
                    #{blockNumber},#{blockHash},#{confirmations},
                    #{blockNumber}+100,#{blockNumber},#{blockHash},#{status})
            """)
    int insertEvent(@Param("network") CregisNetwork network,@Param("userId") long userId, @Param("projectId") long projectId,
                    @Param("cid") long cid, @Param("txid") String txid,
                    @Param("logIndex") int logIndex, @Param("address") String address,
                    @Param("amount") BigDecimal amount, @Param("fee") BigDecimal fee,
                    @Param("net") BigDecimal net, @Param("blockNumber") long blockNumber,
                    @Param("blockHash") String blockHash, @Param("confirmations") int confirmations,
                    @Param("status") String status);

    @Update("UPDATE nx_cregis_deposit_event SET transfer_from_address=#{sender} WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND cid=#{cid} AND (transfer_from_address IS NULL OR transfer_from_address=#{sender})")
    int recordTransferSender(@Param("network") CregisNetwork network,@Param("projectId") long projectId,@Param("cid") long cid,@Param("sender") String sender);

    @Select("SELECT id FROM nx_cregis_deposit_event WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND cid=#{cid}")
    Long eventId(@Param("network") CregisNetwork network,@Param("projectId") long projectId, @Param("cid") long cid);

    @Select("""
            SELECT usdt_available AS usdtAvailable,cregis_risk_held AS cregisRiskHeld,version FROM nx_user_wallet
             WHERE user_id=#{userId} AND is_deleted=0 FOR UPDATE
            """)
    Map<String, Object> lockWallet(@Param("userId") long userId);
    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available+#{amount},
              cumulative_deposit_usdt=cumulative_deposit_usdt+#{amount},version=version+1
             WHERE user_id=#{userId} AND version=#{version} AND is_deleted=0
            """)
    int creditWallet(@Param("amount") BigDecimal amount, @Param("userId") long userId,
                     @Param("version") long version);
    @Insert("""
            INSERT INTO nx_wallet_ledger
            (biz_no,user_id,biz_type,asset,direction,amount,balance_after,status,remark)
            VALUES (#{bizNo},#{userId},'CHAIN_TOPUP','USDT','IN',#{amount},#{balanceAfter},'SUCCESS',#{remark})
            """)
    int insertLedger(@Param("bizNo") String bizNo, @Param("userId") long userId,
                     @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter,
                     @Param("remark") String remark);
    @Select("SELECT id FROM nx_wallet_ledger WHERE biz_no=#{bizNo} AND asset='USDT' AND direction='IN'")
    Long ledgerId(@Param("bizNo") String bizNo);
    @Insert("""
            INSERT INTO nx_deposit_order
            (user_id,deposit_no,chain_name,chain_tx_hash,chain_log_index,asset,amount,confirmations,status,
             ledger_id,confirmed_at,credited_at)
            VALUES (#{userId},#{bizNo},#{network.depositChainName,javaType=java.lang.String},#{txid},#{logIndex},'USDT',#{amount},
                    #{confirmations},'CREDITED',#{ledgerId},NOW(),NOW())
            """)
    int insertDepositOrder(@Param("network") CregisNetwork network,@Param("userId") long userId, @Param("bizNo") String bizNo,
                           @Param("txid") String txid, @Param("logIndex") int logIndex,
                           @Param("amount") BigDecimal amount,
                           @Param("confirmations") int confirmations, @Param("ledgerId") long ledgerId);
    @Update("UPDATE nx_cregis_deposit_event SET ledger_id=#{ledgerId},credited_at=NOW() WHERE id=#{eventId}")
    int linkLedger(@Param("ledgerId") long ledgerId, @Param("eventId") long eventId);
    @Update("""
            UPDATE nx_cregis_deposit_event SET status='CREDITED',fee_amount=#{fee},
              net_amount=#{net},confirmations=#{confirmations}
             WHERE id=#{id} AND status='REVIEW_HOLD' AND ledger_id IS NULL
            """)
    int releaseReviewedEvent(@Param("id") long id, @Param("fee") BigDecimal fee,
                             @Param("net") BigDecimal net, @Param("confirmations") int confirmations);

    @Select("""
            SELECT id,chain_id AS chainId,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
                   net_amount AS netAmount,block_number AS blockNumber,block_hash AS blockHash,
                   log_index AS logIndex,last_canonical_checked_block AS lastCheckedBlock,
                   canonical_until AS canonicalUntil
              FROM nx_cregis_deposit_event
             WHERE project_id=#{projectId} AND status='CREDITED'
               AND last_canonical_checked_block < canonical_until
             ORDER BY last_canonical_checked_block,id LIMIT 50
            """)
    List<Map<String, Object>> canonicalPending(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_deposit_event
               SET last_canonical_checked_block=#{next},last_canonical_checked_hash=#{hash}
             WHERE id=#{id} AND status='CREDITED' AND last_canonical_checked_block=#{expected}
               AND #{next}>#{expected} AND #{next}<=canonical_until
            """)
    int advanceCanonical(@Param("id") long id, @Param("expected") long expected,
                         @Param("next") long next, @Param("hash") String hash);
    @Update("""
            UPDATE nx_cregis_deposit_event SET status=#{status}
             WHERE id=#{id} AND status='CREDITED'
            """)
    int markCreditedIncident(@Param("id") long id, @Param("status") String status);
    @Update("""
            UPDATE nx_user SET status='FROZEN',c2_freeze_source='CREGIS_DEPOSIT_INCIDENT',
              c2_freeze_source_ref=#{sourceRef},c2_freeze_reason=#{reason},
              c2_freeze_operator='cregis-reconciler',c2_frozen_at=NOW(),updated_at=NOW()
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
            """)
    int freezeUser(@Param("userId") long userId, @Param("sourceRef") String sourceRef,
                   @Param("reason") String reason);
    @Update("""
            UPDATE nx_user_session SET revoked_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR),
              updated_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
             WHERE user_id=#{userId} AND revoked_at IS NULL AND is_deleted=0
            """)
    int revokeUserSessions(@Param("userId") long userId);
    @Update("""
            UPDATE nx_user_wallet SET usdt_available=usdt_available-#{amount},
              cregis_risk_held=cregis_risk_held+#{amount},version=version+1
             WHERE user_id=#{userId} AND version=#{version} AND usdt_available>=#{amount} AND is_deleted=0
            """)
    int reserveRiskAmount(@Param("userId") long userId, @Param("version") long version,
                          @Param("amount") BigDecimal amount);
    @Insert("""
            INSERT INTO nx_wallet_ledger
              (biz_no,user_id,biz_type,asset,direction,amount,balance_after,status,remark)
            VALUES (#{bizNo},#{userId},'CREGIS_RISK_HOLD','USDT','OUT',#{amount},#{balanceAfter},
                    'SUCCESS',#{remark})
            """)
    int insertRiskHoldLedger(@Param("bizNo") String bizNo, @Param("userId") long userId,
                             @Param("amount") BigDecimal amount,
                             @Param("balanceAfter") BigDecimal balanceAfter,
                             @Param("remark") String remark);
    @Insert("""
            INSERT INTO nx_cregis_deposit_incident
              (event_id,project_id,cid,user_id,kind,held_amount)
            VALUES (#{eventId},#{projectId},#{cid},#{userId},#{kind},#{heldAmount})
            """)
    int insertIncident(@Param("eventId") long eventId, @Param("projectId") long projectId,
                       @Param("cid") long cid, @Param("userId") long userId,
                       @Param("kind") String kind, @Param("heldAmount") BigDecimal heldAmount);
    @Select("SELECT id FROM nx_cregis_deposit_incident WHERE event_id=#{eventId} AND kind=#{kind} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Long latestIncidentId(@Param("eventId") long eventId,@Param("kind") String kind);

    @Select("SELECT UNIX_TIMESTAMP(MIN(created_at)) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String}")
    Long firstAddressSecond(@Param("network") CregisNetwork network,@Param("projectId") long projectId);
    @Insert("INSERT IGNORE INTO nx_cregis_reconcile_watermark(id,complete_through) VALUES(#{network.cursorId,javaType=int},#{second})")
    int ensureWatermark(@Param("network") CregisNetwork network,@Param("second") long second);
    @Select("SELECT complete_through FROM nx_cregis_reconcile_watermark WHERE id=#{network.cursorId,javaType=int}")
    Long reconcileWatermark(@Param("network") CregisNetwork network);
    @Update("""
            UPDATE nx_cregis_reconcile_watermark
               SET active_run_id=#{runId},lease_until=DATE_ADD(NOW(), INTERVAL 30 MINUTE)
             WHERE id=#{network.cursorId,javaType=int} AND (active_run_id IS NULL OR lease_until<NOW())
            """)
    int claimReconcileLease(@Param("network") CregisNetwork network,@Param("runId") String runId);
    @Select("SELECT active_run_id FROM nx_cregis_reconcile_watermark WHERE id=#{network.cursorId,javaType=int}")
    String activeReconcileRun(@Param("network") CregisNetwork network);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_reconcile_watermark w
              JOIN nx_cregis_reconcile_run r ON r.run_id=w.last_run_id
             WHERE w.id=1 AND r.chain_id='2510' AND r.project_id=#{projectId} AND r.status='COMPLETE'
               AND r.completed_at>=DATE_SUB(NOW(), INTERVAL 15 MINUTE)
               AND w.complete_through>=UNIX_TIMESTAMP(NOW())-900
               AND NOT EXISTS(SELECT 1 FROM nx_cregis_reconcile_run later
                               WHERE later.project_id=r.project_id AND later.chain_id=r.chain_id
                                 AND later.status='INCOMPLETE'
                                 AND later.created_at>=r.completed_at)
            """)
    int recentCompleteReconcileCount(@Param("projectId") long projectId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_reconcile_watermark w
              JOIN nx_cregis_reconcile_run r ON r.run_id=w.last_run_id
             WHERE w.id=#{network.cursorId,javaType=int} AND r.chain_id=#{network.chainId,javaType=java.lang.String} AND r.project_id=#{projectId} AND r.status='COMPLETE'
               AND r.completed_at>=DATE_SUB(NOW(), INTERVAL 15 MINUTE)
               AND w.complete_through>=UNIX_TIMESTAMP(NOW())-900
               AND NOT EXISTS(SELECT 1 FROM nx_cregis_reconcile_run later
                               WHERE later.project_id=r.project_id AND later.chain_id=r.chain_id
                                 AND later.status='INCOMPLETE'
                                 AND later.created_at>=r.completed_at)
            """)
    int recentCompleteReconcileCountForNetwork(@Param("network") CregisNetwork network,@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_reconcile_run SET status='INCOMPLETE',
              failure_code='CREGIS_RECONCILE_INTERRUPTED',completed_at=NOW()
             WHERE project_id=#{projectId} AND chain_id=#{network.chainId,javaType=java.lang.String} AND status='RUNNING' AND run_id<>#{runId}
            """)
    int failInterruptedReconcileRuns(@Param("network") CregisNetwork network,@Param("projectId") long projectId, @Param("runId") String runId);
    @Update("""
            UPDATE nx_cregis_reconcile_watermark SET active_run_id=NULL,lease_until=NULL
             WHERE id=#{network.cursorId,javaType=int} AND active_run_id=#{runId}
            """)
    int releaseReconcileLease(@Param("network") CregisNetwork network,@Param("runId") String runId);
    @Select("SELECT complete_through FROM nx_cregis_reconcile_watermark WHERE id=#{network.cursorId,javaType=int} FOR UPDATE")
    Long lockReconcileWatermark(@Param("network") CregisNetwork network);
    @Update("""
            UPDATE nx_cregis_reconcile_watermark
               SET complete_through=#{end},last_run_id=#{runId}
             WHERE id=#{network.cursorId,javaType=int} AND complete_through=#{expected}
            """)
    int advanceReconcileWatermark(@Param("network") CregisNetwork network,@Param("expected") long expected,
                                  @Param("end") long end, @Param("runId") String runId);
    @Insert("""
            INSERT INTO nx_cregis_reconcile_run
              (run_id,project_id,chain_id,window_start,window_end,status)
            VALUES(#{runId},#{projectId},#{network.chainId,javaType=java.lang.String},#{start},#{end},'RUNNING')
            """)
    int createReconcileRun(@Param("network") CregisNetwork network,@Param("runId") String runId, @Param("projectId") long projectId,
                           @Param("start") long start, @Param("end") long end);
    @Update("""
            UPDATE nx_cregis_reconcile_run SET status='COMPLETE',provider_total=#{total},
              row_count=#{count},unique_cid_count=#{uniqueCount},full_row_hash=#{hash},
              stable_passes=2,chain_cursor_block=#{cursor},chain_cursor_hash=#{chainHash},
              completed_at=NOW()
            WHERE run_id=#{runId} AND status='RUNNING'
            """)
    int completeReconcileRun(@Param("runId") String runId, @Param("total") long total,
                             @Param("count") long count, @Param("uniqueCount") long uniqueCount,
                             @Param("hash") String hash, @Param("cursor") long cursor,
                             @Param("chainHash") String chainHash);
    @Update("""
            UPDATE nx_cregis_reconcile_run SET status='INCOMPLETE',failure_code=#{reason},
              completed_at=NOW() WHERE run_id=#{runId} AND status='RUNNING'
            """)
    int failReconcileRun(@Param("runId") String runId, @Param("reason") String reason);
    @Select("""
            SELECT chain_id AS chainId,CASE chain_id WHEN '60' THEN 'ERC20' ELSE 'BEP20' END AS network,run_id AS runId,window_start AS windowStart,window_end AS windowEnd,
              status,provider_total AS providerTotal,row_count AS rowCount,
              unique_cid_count AS uniqueCidCount,full_row_hash AS fullRowHash,
              stable_passes AS stablePasses,chain_cursor_block AS chainCursorBlock,
              chain_cursor_hash AS chainCursorHash,
              failure_code AS failureCode,created_at AS createdAt,completed_at AS completedAt
            FROM nx_cregis_reconcile_run WHERE project_id=#{projectId}
            ORDER BY created_at DESC LIMIT 10
            """)
    List<Map<String, Object>> reconcileRuns(@Param("projectId") long projectId);
    @Select("""
            SELECT id,chain_id AS chainId,cid,txid,address,gross_amount AS grossAmount,status,block_number AS blockNumber
            FROM nx_cregis_deposit_event WHERE project_id=#{projectId}
              AND status IN ('CREDITED','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')
              AND id>#{afterId}
            ORDER BY id LIMIT 100
            """)
    List<Map<String, Object>> creditedEventsForAudit(@Param("projectId") long projectId,
                                                     @Param("afterId") long afterId);

    @Insert("""
            INSERT INTO nx_cregis_review_case
              (project_id,event_id,action,reason,evidence_hash,maker_id,status)
            VALUES(#{projectId},#{eventId},'CREDIT_REVIEW_HOLD',#{reason},#{evidenceHash},
                   #{makerId},'MAKER_DONE')
            """)
    int createReviewCase(@Param("projectId") long projectId, @Param("eventId") long eventId,
                         @Param("reason") String reason, @Param("evidenceHash") String evidenceHash,
                         @Param("makerId") long makerId);
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_review_case WHERE project_id=#{projectId}
              AND event_id=#{eventId} AND action='CREDIT_REVIEW_HOLD' AND status='MAKER_DONE'
            """)
    int pendingReviewCaseCount(@Param("projectId") long projectId, @Param("eventId") long eventId);
    @Select("""
            SELECT id FROM nx_cregis_review_case WHERE project_id=#{projectId}
              AND event_id=#{eventId} AND action='CREDIT_REVIEW_HOLD'
            """)
    Long reviewCaseId(@Param("projectId") long projectId, @Param("eventId") long eventId);
    @Select("""
            SELECT id,project_id AS projectId,event_id AS eventId,action,reason,
              evidence_hash AS evidenceHash,maker_id AS makerId,checker_id AS checkerId,
              status,version FROM nx_cregis_review_case WHERE id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockReviewCase(@Param("id") long id);
    @Select("""
            SELECT id,project_id AS projectId,event_id AS eventId,maker_id AS makerId,
                   evidence_hash AS evidenceHash,status,version
              FROM nx_cregis_review_case WHERE id=#{id}
            """)
    Map<String, Object> reviewCaseSnapshot(@Param("id") long id);
    @Select("""
            SELECT id,chain_id AS chainId,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
                   status,block_number AS blockNumber,block_hash AS blockHash,
                   log_index AS logIndex FROM nx_cregis_deposit_event WHERE id=#{id}
            """)
    Map<String, Object> reviewEventSnapshot(@Param("id") long id);
    @Select("""
            SELECT c.id,c.event_id AS eventId,e.cid,e.user_id AS userId,
              e.gross_amount AS grossAmount,p.fee_usdt AS feeAmount,e.gross_amount - p.fee_usdt AS proposedNetAmount,
              e.address,e.txid,
              e.block_number AS blockNumber,e.block_hash AS blockHash,
              e.log_index AS logIndex,e.confirmations,c.action,c.reason,
              c.evidence_hash AS evidenceHash,c.maker_id AS makerId,
              c.checker_id AS checkerId,c.status,c.version,
              c.created_at AS createdAt,c.checked_at AS checkedAt
            FROM nx_cregis_review_case c
              JOIN nx_cregis_deposit_event e ON e.id=c.event_id
              LEFT JOIN nx_cregis_deposit_policy_snapshot s ON s.project_id=e.project_id AND s.chain_id=CASE e.chain_id WHEN '60' THEN 'ETH' ELSE 'BSC' END AND s.token_id=CASE e.chain_id WHEN '60' THEN 'USDT-ERC20' ELSE 'USDT-BEP20' END AND s.txid=e.txid
              LEFT JOIN nx_cregis_deposit_policy p ON p.version=s.policy_version
            WHERE c.project_id=#{projectId} AND c.status='MAKER_DONE'
              AND (#{beforeId}=0 OR c.id<#{beforeId})
            ORDER BY c.id DESC LIMIT 50
            """)
    List<Map<String, Object>> reviewCases(@Param("projectId") long projectId,
                                          @Param("beforeId") long beforeId);
    @Select("SELECT COUNT(*) FROM nx_cregis_review_case WHERE project_id=#{projectId} AND status='MAKER_DONE'")
    int openReviewCaseCount(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_review_case SET checker_id=#{checkerId},status=#{status},
              version=version+1,checked_at=NOW()
            WHERE id=#{id} AND status='MAKER_DONE' AND version=#{expectedVersion}
              AND maker_id<>#{checkerId}
            """)
    int checkReviewCase(@Param("id") long id, @Param("checkerId") long checkerId,
                        @Param("status") String status, @Param("expectedVersion") long expectedVersion);
    @Insert("""
            INSERT INTO nx_cregis_switch_case
              (project_id,expected_version,assign_enabled,credit_enabled,payout_enabled,reason,maker_id,status)
            VALUES(#{projectId},#{version},#{assign},#{credit},#{payout},#{reason},#{makerId},'MAKER_DONE')
            """)
    int createSwitchCase(@Param("projectId") long projectId, @Param("version") long version,
                         @Param("assign") int assign, @Param("credit") int credit, @Param("payout") int payout,
                         @Param("reason") String reason, @Param("makerId") long makerId);
    @Select("SELECT LAST_INSERT_ID()")
    Long lastInsertId();
    @Select("""
            SELECT id,project_id AS projectId,expected_version AS expectedVersion,
              assign_enabled AS assignEnabled,credit_enabled AS creditEnabled,payout_enabled AS payoutEnabled,
              reason,maker_id AS makerId,status
            FROM nx_cregis_switch_case WHERE id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockSwitchCase(@Param("id") long id);
    @Select("""
            SELECT id,expected_version AS expectedVersion,assign_enabled AS assignEnabled,
              credit_enabled AS creditEnabled,payout_enabled AS payoutEnabled,reason,
              maker_id AS makerId,checker_id AS checkerId,status,
              created_at AS createdAt,checked_at AS checkedAt
            FROM nx_cregis_switch_case WHERE project_id=#{projectId}
            ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> switchCases(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_switch_case SET checker_id=#{checkerId},status=#{status},checked_at=NOW()
             WHERE id=#{id} AND project_id=#{projectId} AND status='MAKER_DONE'
               AND maker_id<>#{checkerId}
            """)
    int checkSwitchCase(@Param("id") long id, @Param("projectId") long projectId,
                        @Param("checkerId") long checkerId, @Param("status") String status);
    default Map<String,Object> poolPolicy() { return poolPolicy(CregisNetwork.BEP20); }
    default int setPoolRefillActive(boolean active) { return setPoolRefillActive(CregisNetwork.BEP20 ,active); }
    default int pausePoolSubmission(String code,int seconds) { return pausePoolSubmission(CregisNetwork.BEP20 ,code,seconds); }
    default Long lockCursor() { return lockCursor(CregisNetwork.BEP20); }
    default int ensureCursor(long block) { return ensureCursor(CregisNetwork.BEP20 ,block); }
    default Long cursor() { return cursor(CregisNetwork.BEP20); }
    default int advanceCursor(long next, long expected) { return advanceCursor(CregisNetwork.BEP20 ,next,expected); }
    default int addressObservationCount(long projectId,
                                String address, long block) { return addressObservationCount(CregisNetwork.BEP20 ,projectId,address,block); }
    default Map<String, Object> observationByLog(long projectId,
                                         String txid, int logIndex) { return observationByLog(CregisNetwork.BEP20 ,projectId,txid,logIndex); }
    default int insertObservation(long projectId, String txid,
                          int logIndex, String address,
                          String amount, long blockNumber,
                          String status) { return insertObservation(CregisNetwork.BEP20 ,projectId,txid,logIndex,address,amount,blockNumber,status); }
    default List<Map<String, Object>> lockEvent(long projectId, long cid) { return lockEvent(CregisNetwork.BEP20 ,projectId,cid); }
    default int insertEvent(long userId, long projectId,
                    long cid, String txid,
                    int logIndex, String address,
                    BigDecimal amount, BigDecimal fee,
                    BigDecimal net, long blockNumber,
                    String blockHash, int confirmations,
                    String status) { return insertEvent(CregisNetwork.BEP20 ,userId,projectId,cid,txid,logIndex,address,amount,fee,net,blockNumber,blockHash,confirmations,status); }
    default int recordTransferSender(long projectId,long cid,String sender) { return recordTransferSender(CregisNetwork.BEP20 ,projectId,cid,sender); }
    default Long eventId(long projectId, long cid) { return eventId(CregisNetwork.BEP20 ,projectId,cid); }
    default int insertDepositOrder(long userId, String bizNo,
                           String txid, int logIndex,
                           BigDecimal amount,
                           int confirmations, long ledgerId) { return insertDepositOrder(CregisNetwork.BEP20 ,userId,bizNo,txid,logIndex,amount,confirmations,ledgerId); }
    default Long firstAddressSecond(long projectId) { return firstAddressSecond(CregisNetwork.BEP20 ,projectId); }
    default int ensureWatermark(long second) { return ensureWatermark(CregisNetwork.BEP20 ,second); }
    default Long reconcileWatermark() { return reconcileWatermark(CregisNetwork.BEP20); }
    default int claimReconcileLease(String runId) { return claimReconcileLease(CregisNetwork.BEP20 ,runId); }
    default String activeReconcileRun() { return activeReconcileRun(CregisNetwork.BEP20); }
    default int failInterruptedReconcileRuns(long projectId, String runId) { return failInterruptedReconcileRuns(CregisNetwork.BEP20 ,projectId,runId); }
    default int releaseReconcileLease(String runId) { return releaseReconcileLease(CregisNetwork.BEP20 ,runId); }
    default Long lockReconcileWatermark() { return lockReconcileWatermark(CregisNetwork.BEP20); }
    default int advanceReconcileWatermark(long expected,
                                  long end, String runId) { return advanceReconcileWatermark(CregisNetwork.BEP20 ,expected,end,runId); }
    default int createReconcileRun(String runId, long projectId,
                           long start, long end) { return createReconcileRun(CregisNetwork.BEP20 ,runId,projectId,start,end); }
}
