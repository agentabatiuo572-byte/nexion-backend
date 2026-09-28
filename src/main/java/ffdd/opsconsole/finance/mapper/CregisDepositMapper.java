package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.CregisDepositEventEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CregisDepositMapper extends BaseMapper<CregisDepositEventEntity> {
    @Select("""
            SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
             AND TABLE_NAME IN ('nx_cregis_deposit_address','nx_cregis_deposit_delivery',
               'nx_cregis_deposit_event','nx_cregis_chain_observation','nx_cregis_chain_cursor',
               'nx_cregis_provision_gate','nx_cregis_deposit_incident')
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
             AND ((COLUMN_NAME='user_id' AND IS_NULLABLE='YES') OR COLUMN_NAME='creation_block')
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
            SELECT id,address,creation_block AS creationBlock FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='VERIFYING'
             ORDER BY id LIMIT 1
            """)
    Map<String, Object> verifyingCandidate(@Param("projectId") long projectId,
                                            @Param("chainId") String chainId);
    @Update("""
            UPDATE nx_cregis_deposit_address SET state='UNASSIGNED'
             WHERE id=#{id} AND state='VERIFYING' AND user_id IS NULL
            """)
    int readyPoolCandidate(@Param("id") long id);
    @Update("""
            UPDATE nx_cregis_deposit_address SET state='UNATTRIBUTED_HOLD'
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND address=#{address}
               AND state IN ('VERIFYING','UNASSIGNED') AND user_id IS NULL
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
    @Update("""
            UPDATE nx_cregis_deposit_address
               SET user_id=#{userId},state='READY',allocation_block=#{block},allocation_hash=#{hash}
             WHERE id=#{id} AND state='UNASSIGNED' AND user_id IS NULL
            """)
    int assignPoolAddress(@Param("id") long id, @Param("userId") long userId,
                          @Param("block") long block, @Param("hash") String hash);
    @Select("SELECT next_block FROM nx_cregis_chain_cursor WHERE id=1 FOR UPDATE")
    Long lockCursor();
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND address=#{address} AND block_number<=#{block}
            """)
    int addressObservationCount(@Param("projectId") long projectId,
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
            SELECT
              COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
                         WHERE project_id=#{projectId} AND status IN
                         ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
              + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                            FROM nx_cregis_chain_observation o
                           WHERE o.project_id=#{projectId} AND o.status<>'MATCHED'
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                             WHERE e.project_id=o.project_id
                                               AND e.txid=o.txid AND e.log_index=o.log_index)),0)
              + COALESCE((SELECT SUM(d.gross_amount) FROM nx_cregis_deposit_delivery d
                           WHERE d.accepted=1 AND d.processed_at IS NULL
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                             WHERE e.project_id=#{projectId} AND e.cid=d.cid)
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                                             WHERE o.project_id=#{projectId} AND o.txid=d.txid)),0)
              + COALESCE((SELECT SUM(d.gross_amount) FROM nx_cregis_deposit_delivery d
                           WHERE d.accepted=1 AND d.reason IN
                             ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD',
                              'PROVIDER_CONFLICT_HOLD')
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                             WHERE e.project_id=#{projectId} AND e.cid=d.cid)
                             AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                                             WHERE o.project_id=#{projectId} AND o.txid=d.txid)),0)
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
                   state=IF(#{assign}=1 OR #{credit}=1,'IDLE','BLOCKED'),
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
            SELECT id,user_id AS userId,address,state,created_at AS createdAt
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND state IN ('UNKNOWN','PROVISIONING','VERIFYING','UNATTRIBUTED_HOLD')
             ORDER BY id LIMIT 50
            """)
    List<Map<String, Object>> uncertainAddresses(@Param("projectId") long projectId);
    @Select("""
            SELECT id,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
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
            SELECT id,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   status,last_error AS lastError,checked_at AS checkedAt
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status IN
               ('PROVIDER_MISSING','UNATTRIBUTED_HOLD','CHAIN_MISMATCH_HOLD')
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> providerMissing(@Param("projectId") long projectId);
    @Select("""
            SELECT d.id,d.cid,d.txid,d.address,d.gross_amount AS grossAmount,
                   d.last_error AS lastError,d.created_at AS createdAt
              FROM nx_cregis_deposit_delivery d
             WHERE d.accepted=1 AND d.processed_at IS NULL AND d.last_error IS NOT NULL
             ORDER BY d.id DESC LIMIT 50
            """)
    List<Map<String, Object>> failedDeliveries(@Param("projectId") long projectId);
    @Select("""
            SELECT id,cid,txid,address,gross_amount AS grossAmount,reason,
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
              SELECT CONCAT('CR-',cid) AS depositId,txid AS txHash,address,
                     gross_amount AS grossAmountUsdt,
                      CASE WHEN status IN ('CREDITED','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')
                           THEN net_amount ELSE 0 END AS creditedUsdt,
                     confirmations,status,UNIX_TIMESTAMP(created_at)*1000 AS createdAt,
                     UNIX_TIMESTAMP(credited_at)*1000 AS creditedAt
                FROM nx_cregis_deposit_event WHERE user_id=#{userId}
              UNION ALL
              SELECT CONCAT('CR-',d.cid),d.txid,d.address,d.gross_amount,0,0,'CONFIRMING',
                     UNIX_TIMESTAMP(MIN(d.created_at))*1000,NULL
                FROM nx_cregis_deposit_delivery d
                JOIN nx_cregis_deposit_address a ON a.project_id=#{projectId} AND a.address=d.address
                 AND a.user_id=#{userId} AND a.state='READY'
               WHERE d.accepted=1 AND d.processed_at IS NULL AND d.cid IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_event e
                                  WHERE e.project_id=#{projectId} AND e.cid=d.cid)
               GROUP BY d.cid,d.txid,d.address,d.gross_amount
            ) deposit_rows ORDER BY createdAt DESC LIMIT 50
            """)
    List<Map<String, Object>> deposits(@Param("userId") long userId, @Param("projectId") long projectId);

    @Insert("""
            INSERT INTO nx_cregis_deposit_delivery
            (payload_sha256,raw_json,cid,txid,address,gross_amount,accepted,reason,
             source_ip,signature_valid,timestamp_valid,ip_valid)
            VALUES (#{hash},#{raw},#{cid},#{txid},#{address},#{amount},#{accepted},#{reason},
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
            SELECT address,user_id AS userId,state,creation_block AS creationBlock,
                   allocation_block AS allocationBlock
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId}
               AND state IN ('VERIFYING','UNASSIGNED','READY','UNATTRIBUTED_HOLD')
             ORDER BY id LIMIT 61
            """)
    List<Map<String, Object>> allocations(@Param("projectId") long projectId,
                                          @Param("chainId") String chainId);

    @Insert("INSERT IGNORE INTO nx_cregis_chain_cursor(id,next_block) VALUES (1,#{block})")
    int ensureCursor(@Param("block") long block);
    @Select("SELECT next_block FROM nx_cregis_chain_cursor WHERE id=1")
    Long cursor();
    @Update("UPDATE nx_cregis_chain_cursor SET next_block=#{next} WHERE id=1 AND next_block=#{expected}")
    int advanceCursor(@Param("next") long next, @Param("expected") long expected);

    @Select("""
            SELECT id,address,raw_amount AS rawAmount,block_number AS blockNumber,status
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND txid=#{txid} AND log_index=#{logIndex}
            """)
    Map<String, Object> observationByLog(@Param("projectId") long projectId,
                                         @Param("txid") String txid, @Param("logIndex") int logIndex);
    @Insert("""
            INSERT INTO nx_cregis_chain_observation
            (project_id,txid,log_index,address,raw_amount,block_number,status)
            VALUES (#{projectId},#{txid},#{logIndex},#{address},#{amount},#{blockNumber},#{status})
            """)
    int insertObservation(@Param("projectId") long projectId, @Param("txid") String txid,
                          @Param("logIndex") int logIndex, @Param("address") String address,
                          @Param("amount") String amount, @Param("blockNumber") long blockNumber,
                          @Param("status") String status);

    @Select("""
            SELECT id,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   block_number AS blockNumber FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status='PROVIDER_MISSING'
             ORDER BY checked_at,id LIMIT 50
            """)
    List<Map<String, Object>> missingObservations(@Param("projectId") long projectId);
    @Select("""
            SELECT id,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
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
            SELECT id,user_id AS userId,txid,log_index AS logIndex,address,
                   gross_amount AS grossAmount,net_amount AS netAmount,status,
                   block_number AS blockNumber,block_hash AS blockHash,confirmations
              FROM nx_cregis_deposit_event WHERE project_id=#{projectId} AND cid=#{cid} FOR UPDATE
            """)
    List<Map<String, Object>> lockEvent(@Param("projectId") long projectId, @Param("cid") long cid);
    @Select("""
            SELECT id,cid,status FROM nx_cregis_deposit_event WHERE id=#{eventId} FOR UPDATE
            """)
    List<Map<String, Object>> lockReviewEvent(@Param("eventId") long eventId);

    @Insert("""
            INSERT INTO nx_cregis_deposit_event
            (user_id,project_id,cid,txid,log_index,address,gross_amount,fee_amount,net_amount,
             block_number,block_hash,confirmations,canonical_until,
             last_canonical_checked_block,last_canonical_checked_hash,status)
            VALUES (#{userId},#{projectId},#{cid},#{txid},#{logIndex},#{address},#{amount},#{fee},#{net},
                    #{blockNumber},#{blockHash},#{confirmations},
                    #{blockNumber}+100,#{blockNumber},#{blockHash},#{status})
            """)
    int insertEvent(@Param("userId") long userId, @Param("projectId") long projectId,
                    @Param("cid") long cid, @Param("txid") String txid,
                    @Param("logIndex") int logIndex, @Param("address") String address,
                    @Param("amount") BigDecimal amount, @Param("fee") BigDecimal fee,
                    @Param("net") BigDecimal net, @Param("blockNumber") long blockNumber,
                    @Param("blockHash") String blockHash, @Param("confirmations") int confirmations,
                    @Param("status") String status);

    @Select("SELECT id FROM nx_cregis_deposit_event WHERE project_id=#{projectId} AND cid=#{cid}")
    Long eventId(@Param("projectId") long projectId, @Param("cid") long cid);

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
            VALUES (#{userId},#{bizNo},'CREGIS_USDT_BEP20',#{txid},#{logIndex},'USDT',#{amount},
                    #{confirmations},'CREDITED',#{ledgerId},NOW(),NOW())
            """)
    int insertDepositOrder(@Param("userId") long userId, @Param("bizNo") String bizNo,
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
            SELECT id,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
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

    @Select("SELECT UNIX_TIMESTAMP(MIN(created_at)) FROM nx_cregis_deposit_address WHERE project_id=#{projectId}")
    Long firstAddressSecond(@Param("projectId") long projectId);
    @Insert("INSERT IGNORE INTO nx_cregis_reconcile_watermark(id,complete_through) VALUES(1,#{second})")
    int ensureWatermark(@Param("second") long second);
    @Select("SELECT complete_through FROM nx_cregis_reconcile_watermark WHERE id=1")
    Long reconcileWatermark();
    @Update("""
            UPDATE nx_cregis_reconcile_watermark
               SET active_run_id=#{runId},lease_until=DATE_ADD(NOW(), INTERVAL 30 MINUTE)
             WHERE id=1 AND (active_run_id IS NULL OR lease_until<NOW())
            """)
    int claimReconcileLease(@Param("runId") String runId);
    @Select("SELECT active_run_id FROM nx_cregis_reconcile_watermark WHERE id=1")
    String activeReconcileRun();
    @Select("""
            SELECT COUNT(*) FROM nx_cregis_reconcile_watermark w
              JOIN nx_cregis_reconcile_run r ON r.run_id=w.last_run_id
             WHERE w.id=1 AND r.project_id=#{projectId} AND r.status='COMPLETE'
               AND r.completed_at>=DATE_SUB(NOW(), INTERVAL 15 MINUTE)
               AND w.complete_through>=UNIX_TIMESTAMP(NOW())-900
               AND NOT EXISTS(SELECT 1 FROM nx_cregis_reconcile_run later
                               WHERE later.project_id=r.project_id
                                 AND later.status='INCOMPLETE'
                                 AND later.created_at>=r.completed_at)
            """)
    int recentCompleteReconcileCount(@Param("projectId") long projectId);
    @Update("""
            UPDATE nx_cregis_reconcile_run SET status='INCOMPLETE',
              failure_code='CREGIS_RECONCILE_INTERRUPTED',completed_at=NOW()
             WHERE project_id=#{projectId} AND status='RUNNING' AND run_id<>#{runId}
            """)
    int failInterruptedReconcileRuns(@Param("projectId") long projectId, @Param("runId") String runId);
    @Update("""
            UPDATE nx_cregis_reconcile_watermark SET active_run_id=NULL,lease_until=NULL
             WHERE id=1 AND active_run_id=#{runId}
            """)
    int releaseReconcileLease(@Param("runId") String runId);
    @Select("SELECT complete_through FROM nx_cregis_reconcile_watermark WHERE id=1 FOR UPDATE")
    Long lockReconcileWatermark();
    @Update("""
            UPDATE nx_cregis_reconcile_watermark
               SET complete_through=#{end},last_run_id=#{runId}
             WHERE id=1 AND complete_through=#{expected}
            """)
    int advanceReconcileWatermark(@Param("expected") long expected,
                                  @Param("end") long end, @Param("runId") String runId);
    @Insert("""
            INSERT INTO nx_cregis_reconcile_run
              (run_id,project_id,window_start,window_end,status)
            VALUES(#{runId},#{projectId},#{start},#{end},'RUNNING')
            """)
    int createReconcileRun(@Param("runId") String runId, @Param("projectId") long projectId,
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
            SELECT run_id AS runId,window_start AS windowStart,window_end AS windowEnd,
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
            SELECT id,cid,txid,address,gross_amount AS grossAmount,status,block_number AS blockNumber
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
            SELECT id,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
                   status,block_number AS blockNumber,block_hash AS blockHash,
                   log_index AS logIndex FROM nx_cregis_deposit_event WHERE id=#{id}
            """)
    Map<String, Object> reviewEventSnapshot(@Param("id") long id);
    @Select("""
            SELECT c.id,c.event_id AS eventId,e.cid,e.user_id AS userId,
              e.gross_amount AS grossAmount,e.gross_amount - 1 AS proposedNetAmount,
              e.address,e.txid,
              e.block_number AS blockNumber,e.block_hash AS blockHash,
              e.log_index AS logIndex,e.confirmations,c.action,c.reason,
              c.evidence_hash AS evidenceHash,c.maker_id AS makerId,
              c.checker_id AS checkerId,c.status,c.version,
              c.created_at AS createdAt,c.checked_at AS checkedAt
            FROM nx_cregis_review_case c
              JOIN nx_cregis_deposit_event e ON e.id=c.event_id
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
              (project_id,expected_version,assign_enabled,credit_enabled,reason,maker_id,status)
            VALUES(#{projectId},#{version},#{assign},#{credit},#{reason},#{makerId},'MAKER_DONE')
            """)
    int createSwitchCase(@Param("projectId") long projectId, @Param("version") long version,
                         @Param("assign") int assign, @Param("credit") int credit,
                         @Param("reason") String reason, @Param("makerId") long makerId);
    @Select("SELECT LAST_INSERT_ID()")
    Long lastInsertId();
    @Select("""
            SELECT id,project_id AS projectId,expected_version AS expectedVersion,
              assign_enabled AS assignEnabled,credit_enabled AS creditEnabled,
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
}
