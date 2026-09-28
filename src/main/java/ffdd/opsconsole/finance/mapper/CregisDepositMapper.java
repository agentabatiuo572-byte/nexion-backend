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
               'nx_cregis_provision_gate')
            """)
    int schemaTableCount();

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
            SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_deposit_order' AND INDEX_NAME='uk_deposit_chain_tx_asset_log'
              AND SEQ_IN_INDEX=1 AND NON_UNIQUE=0
            """)
    int depositOrderLogIndexCount();

    @Update("UPDATE nx_cregis_provision_gate SET state='BUSY' WHERE id=1 AND state='IDLE'")
    int claimProvisionGate();
    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{projectId} AND chain_id=#{chainId}")
    int allocatedAddressCount(@Param("projectId") long projectId, @Param("chainId") String chainId);
    @Update("UPDATE nx_cregis_provision_gate SET state='IDLE' WHERE id=1 AND state='BUSY'")
    int releaseProvisionGate();
    @Update("UPDATE nx_cregis_provision_gate SET state='BLOCKED' WHERE id=1 AND state='BUSY'")
    int blockProvisionGate();

    @Select("""
            SELECT state,updated_at AS updatedAt FROM nx_cregis_provision_gate WHERE id=1
            """)
    Map<String, Object> provisionGate();
    @Select("""
            SELECT id,user_id AS userId,address,state,created_at AS createdAt
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND state IN ('UNKNOWN','PROVISIONING')
             ORDER BY id LIMIT 50
            """)
    List<Map<String, Object>> uncertainAddresses(@Param("projectId") long projectId);
    @Select("""
            SELECT id,user_id AS userId,cid,txid,address,gross_amount AS grossAmount,
                   status,created_at AS createdAt
              FROM nx_cregis_deposit_event
             WHERE project_id=#{projectId} AND status IN ('DUST_HOLD','REVIEW_HOLD')
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> heldDeposits(@Param("projectId") long projectId);
    @Select("""
            SELECT id,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   status,last_error AS lastError,checked_at AS checkedAt
              FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status='PROVIDER_MISSING'
             ORDER BY id DESC LIMIT 50
            """)
    List<Map<String, Object>> providerMissing(@Param("projectId") long projectId);
    @Select("""
            SELECT d.id,d.cid,d.txid,d.address,d.gross_amount AS grossAmount,
                   d.last_error AS lastError,d.created_at AS createdAt
              FROM nx_cregis_deposit_delivery d
              JOIN nx_cregis_deposit_address a ON a.project_id=#{projectId}
                   AND a.address=d.address
             WHERE d.accepted=1 AND d.processed_at IS NULL AND d.last_error IS NOT NULL
             ORDER BY d.id DESC LIMIT 50
            """)
    List<Map<String, Object>> failedDeliveries(@Param("projectId") long projectId);

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
                     CASE WHEN status='CREDITED' THEN net_amount ELSE 0 END AS creditedUsdt,
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
            ) rows ORDER BY createdAt DESC LIMIT 50
            """)
    List<Map<String, Object>> deposits(@Param("userId") long userId, @Param("projectId") long projectId);

    @Insert("""
            INSERT INTO nx_cregis_deposit_delivery
            (payload_sha256,raw_json,cid,txid,address,gross_amount,accepted,reason)
            VALUES (#{hash},#{raw},#{cid},#{txid},#{address},#{amount},#{accepted},#{reason})
            """)
    int insertDelivery(@Param("hash") String hash, @Param("raw") String raw,
                       @Param("cid") Long cid, @Param("txid") String txid,
                       @Param("address") String address, @Param("amount") BigDecimal amount,
                       @Param("accepted") int accepted, @Param("reason") String reason);

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
            SELECT user_id AS userId,allocation_block AS allocationBlock
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND address=#{address} AND state='READY'
            """)
    List<Map<String, Object>> addressOwner(@Param("projectId") long projectId,
                                           @Param("chainId") String chainId,
                                           @Param("address") String address);

    @Select("""
            SELECT address,user_id AS userId,allocation_block AS allocationBlock
              FROM nx_cregis_deposit_address
             WHERE project_id=#{projectId} AND chain_id=#{chainId} AND state='READY'
             ORDER BY id LIMIT 51
            """)
    List<Map<String, Object>> allocations(@Param("projectId") long projectId,
                                          @Param("chainId") String chainId);

    @Insert("INSERT IGNORE INTO nx_cregis_chain_cursor(id,next_block) VALUES (1,#{block})")
    int ensureCursor(@Param("block") long block);
    @Select("SELECT next_block FROM nx_cregis_chain_cursor WHERE id=1")
    Long cursor();
    @Update("UPDATE nx_cregis_chain_cursor SET next_block=#{next} WHERE id=1 AND next_block=#{expected}")
    int advanceCursor(@Param("next") long next, @Param("expected") long expected);

    @Insert("""
            INSERT IGNORE INTO nx_cregis_chain_observation
            (project_id,txid,log_index,address,raw_amount,block_number,status)
            VALUES (#{projectId},#{txid},#{logIndex},#{address},#{amount},#{blockNumber},'PROVIDER_MISSING')
            """)
    int insertObservation(@Param("projectId") long projectId, @Param("txid") String txid,
                          @Param("logIndex") int logIndex, @Param("address") String address,
                          @Param("amount") String amount, @Param("blockNumber") long blockNumber);

    @Select("""
            SELECT id,txid,log_index AS logIndex,address,raw_amount AS rawAmount,
                   block_number AS blockNumber FROM nx_cregis_chain_observation
             WHERE project_id=#{projectId} AND status='PROVIDER_MISSING'
             ORDER BY checked_at,id LIMIT 50
            """)
    List<Map<String, Object>> missingObservations(@Param("projectId") long projectId);
    @Update("UPDATE nx_cregis_chain_observation SET checked_at=NOW(),last_error=#{reason} WHERE id=#{id}")
    int touchObservation(@Param("id") long id, @Param("reason") String reason);
    @Update("UPDATE nx_cregis_chain_observation SET status='MATCHED',checked_at=NOW() WHERE id=#{id}")
    int matchObservation(@Param("id") long id);

    @Select("""
            SELECT user_id AS userId,txid,log_index AS logIndex,address,
                   gross_amount AS grossAmount,status
              FROM nx_cregis_deposit_event WHERE project_id=#{projectId} AND cid=#{cid} FOR UPDATE
            """)
    List<Map<String, Object>> lockEvent(@Param("projectId") long projectId, @Param("cid") long cid);

    @Insert("""
            INSERT INTO nx_cregis_deposit_event
            (user_id,project_id,cid,txid,log_index,address,gross_amount,fee_amount,net_amount,
             block_number,block_hash,confirmations,status)
            VALUES (#{userId},#{projectId},#{cid},#{txid},#{logIndex},#{address},#{amount},#{fee},#{net},
                    #{blockNumber},#{blockHash},#{confirmations},#{status})
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
            SELECT usdt_available AS usdtAvailable,version FROM nx_user_wallet
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
}
