package ffdd.opsconsole.finance.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface EarningsReleaseMapper {
    String FUND_ENTRY="SELECT id,entry_no entryNo,user_id userId,cluster_id clusterId,source_type sourceType,"
        +"source_ref sourceRef,asset,amount,bucket,status,idempotency_key idempotencyKey,source_environment sourceEnvironment,"
        +"is_deleted isDeleted,recovered_amount recoveredAmount,source_debit_baseline debitBaseline,source_wallet_id sourceWalletId,"
        +"released_at releasedAt,release_source releaseSource,created_at createdAt,updated_at updatedAt FROM nx_earnings_release_entry";
    String FUND_RECOVERY="SELECT id,recovery_no recoveryNo,entry_no entryNo,user_id userId,source_type sourceType,source_ref sourceRef,"
        +"asset,source_environment sourceEnvironment,requested,recovered,outstanding,cumulative_recovered cumulativeRecovered,"
        +"remaining_entitlement remainingEntitlement,bucket,balance_before balanceBefore,balance_after balanceAfter,"
        +"debit_counter_before debitCounterBefore,source_debit_baseline debitBaseline,evidence_status evidenceStatus,"
        +"reason,actor,created_at createdAt FROM nx_earnings_source_recovery";
    String FUND_APPLIED_PROOF="SELECT proof_id proofId,user_id userId,device_id deviceId,command_version commandVersion,"
        +"proof_hash proofHash,executor_mode executorMode,earnings_consumed_at earningsConsumedAt FROM nx_janus_applied_proof";
    String FUND_RELEASE_AUDIT="SELECT id,action,resource_type resourceType,resource_id resourceId,result,detail_json detailJson,"
        +"is_deleted isDeleted,created_at createdAt FROM nx_audit_log";

    @Select(FUND_ENTRY+" WHERE entry_no=#{entryNo} FOR SHARE")
    @org.apache.ibatis.annotations.Options(useCache=false,flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Map<String,Object> fundEntry(@Param("entryNo") String entryNo);
    @Select(FUND_RECOVERY+" WHERE recovery_no=#{recoveryNo} FOR SHARE")
    @org.apache.ibatis.annotations.Options(useCache=false,flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Map<String,Object> fundRecovery(@Param("recoveryNo") String recoveryNo);
    @Select(FUND_APPLIED_PROOF+" WHERE proof_id=#{proofId} FOR SHARE")
    @org.apache.ibatis.annotations.Options(useCache=false,flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Map<String,Object> fundAppliedProof(@Param("proofId") String proofId);
    @Select(FUND_RELEASE_AUDIT+" WHERE action=#{action} AND resource_type='EARNINGS_RELEASE_ENTRY' AND resource_id=#{entryNo} ORDER BY id DESC LIMIT 1 FOR SHARE")
    @org.apache.ibatis.annotations.Options(useCache=false,flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Map<String,Object> fundReleaseAudit(@Param("entryNo") String entryNo,@Param("action") String action);
    @Select("SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=#{userId} ORDER BY operation_id DESC LIMIT 1 FOR SHARE")
    @org.apache.ibatis.annotations.Options(useCache=false,flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Long previousFundOperation(@Param("userId") long userId);
    @Select("SELECT user_id userId,source_environment sourceEnvironment FROM nx_earnings_release_entry WHERE entry_no=#{entryNo}")
    Map<String,Object> fundEntryRoute(@Param("entryNo") String entryNo);
    @Insert("INSERT IGNORE INTO nx_earnings_release_entry(entry_no,user_id,cluster_id,source_type,source_ref,asset,amount,bucket,status,idempotency_key,source_environment,is_deleted,source_debit_baseline,source_wallet_id) SELECT #{entryNo},#{userId},#{clusterId},#{sourceType},#{sourceRef},#{asset},#{amount},#{bucket},'ACTIVE',#{idempotencyKey},#{sourceEnvironment},0,CASE #{asset} WHEN 'USDT' THEN earnings_usdt_debited WHEN 'NEX' THEN earnings_nex_debited END,id FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0")
    int insert(EntryWrite write);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND is_deleted=0 AND sandbox=#{expectedSandbox} FOR UPDATE")
    Long lockRecoveryUser(@Param("userId") Long userId, @Param("expectedSandbox") int expectedSandbox);
    @Select("SELECT id walletId,usdt_available usdtAvailable,nex_available nexAvailable,earnings_usdt_debited usdtDebited,earnings_nex_debited nexDebited FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 AND sandbox=#{expectedSandbox} FOR UPDATE")
    RecoveryWallet lockRecoveryWallet(@Param("userId") Long userId, @Param("expectedSandbox") int expectedSandbox);
    @Select("SELECT entry_no entryNo,user_id userId,source_type sourceType,source_ref sourceRef,asset,amount,bucket,status,source_environment sourceEnvironment,is_deleted isDeleted,recovered_amount recoveredAmount,source_debit_baseline debitBaseline,source_wallet_id sourceWalletId FROM nx_earnings_release_entry WHERE entry_no=#{entryNo} FOR UPDATE")
    RecoveryEntry lockRecoveryEntry(@Param("entryNo") String entryNo);
    @Update("""
            UPDATE nx_user_wallet SET
                usdt_available=usdt_available-CASE WHEN #{asset}='USDT' THEN #{amount} ELSE 0 END,
                nex_available=nex_available-CASE WHEN #{asset}='NEX' THEN #{amount} ELSE 0 END,
                version=version+1,updated_at=NOW()
             WHERE user_id=#{userId} AND sandbox=#{expectedSandbox} AND is_deleted=0
               AND #{amount}>0 AND #{asset} IN ('USDT','NEX')
               AND CASE #{asset} WHEN 'USDT' THEN usdt_available WHEN 'NEX' THEN nex_available END>=#{amount}
            """)
    int debitRecoveredReward(@Param("userId") Long userId, @Param("asset") String asset,
            @Param("amount") BigDecimal amount, @Param("expectedSandbox") int expectedSandbox);
    @Update("""
            UPDATE nx_earnings_release_entry SET recovered_amount=recovered_amount+#{amount},updated_at=NOW()
             WHERE entry_no=#{entryNo} AND recovered_amount=#{expectedRecovered} AND is_deleted=0
               AND status='ACTIVE' AND #{amount}>0 AND recovered_amount+#{amount}<=amount
            """)
    int addRecoveredAmount(@Param("entryNo") String entryNo, @Param("expectedRecovered") BigDecimal expectedRecovered,
            @Param("amount") BigDecimal amount);
    @Select("""
            SELECT r.recovered
              FROM nx_earnings_source_recovery r
              JOIN nx_earnings_release_entry e ON e.entry_no=r.entry_no
             WHERE r.user_id=#{userId} AND e.user_id=#{userId} AND e.source_wallet_id=#{walletId}
               AND r.asset=#{asset} AND e.asset=#{asset}
               AND r.source_environment=#{environment} AND e.source_environment=#{environment}
               AND r.entry_no<>#{entryNo} AND r.recovered>0
               AND r.evidence_status='TRACKED_CONSERVATIVE'
               AND r.debit_counter_before>=#{baseline}
               AND r.debit_counter_before+r.recovered<=#{debited}
             ORDER BY r.id FOR UPDATE
            """)
    List<BigDecimal> otherSourceRecoveriesSinceCredit(@Param("userId") Long userId,
            @Param("walletId") Long walletId, @Param("asset") String asset,
            @Param("environment") String environment, @Param("entryNo") String entryNo,
            @Param("baseline") BigDecimal baseline, @Param("debited") BigDecimal debited);
    @Insert("""
            INSERT INTO nx_earnings_source_recovery
              (recovery_no,entry_no,user_id,source_type,source_ref,asset,source_environment,requested,recovered,
               outstanding,cumulative_recovered,remaining_entitlement,bucket,balance_before,balance_after,
               debit_counter_before,source_debit_baseline,evidence_status,reason,actor)
            VALUES (#{receipt.recoveryNo},#{receipt.entryNo},#{receipt.userId},#{sourceType},#{sourceRef},
               #{receipt.asset},#{sourceEnvironment},#{receipt.requested},#{receipt.recovered},#{receipt.outstanding},
               #{receipt.cumulativeRecovered},#{receipt.remainingEntitlement},#{receipt.bucket},#{balanceBefore},
               #{receipt.balanceAfter},#{debitCounterBefore},#{sourceDebitBaseline},#{receipt.evidenceStatus},#{reason},#{actor})
            """)
    int insertRecovery(RecoveryWrite write);
    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND is_deleted=0 AND status='ACTIVE' AND sandbox=#{expectedSandbox} FOR UPDATE")
    Long lockCreditUser(@Param("userId") Long userId, @Param("expectedSandbox") int expectedSandbox);
    @Select("SELECT user_id FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 AND sandbox=#{expectedSandbox} FOR UPDATE")
    Long lockCreditWallet(@Param("userId") Long userId, @Param("expectedSandbox") int expectedSandbox);
    @Select("SELECT entry_no entryNo,user_id userId,source_type sourceType,source_ref sourceRef,asset,amount,status,idempotency_key idempotencyKey,source_environment sourceEnvironment,is_deleted isDeleted FROM nx_earnings_release_entry WHERE source_type=#{sourceType} AND source_ref=#{sourceRef} AND user_id=#{userId} LIMIT 1 FOR UPDATE")
    ExistingEntry findBySource(@Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef,
                               @Param("userId") Long userId);
    @Update("""
            UPDATE nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.is_deleted=0 AND u.status='ACTIVE'
               SET w.usdt_available=w.usdt_available+#{amount},
                   w.lifetime_earned=w.lifetime_earned+#{amount},w.version=w.version+1,w.updated_at=NOW()
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND u.sandbox=#{expectedSandbox} AND w.sandbox=#{expectedSandbox}
            """)
    int creditUsdt(@Param("userId") Long userId,@Param("amount") BigDecimal amount,
                   @Param("sourceEnvironment") String sourceEnvironment,
                   @Param("expectedSandbox") int expectedSandbox);
    @Update("""
            UPDATE nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.is_deleted=0 AND u.status='ACTIVE'
               SET w.nex_available=w.nex_available+#{amount},
                   w.lifetime_earned=w.lifetime_earned+#{amount},w.version=w.version+1,w.updated_at=NOW()
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND u.sandbox=#{expectedSandbox} AND w.sandbox=#{expectedSandbox}
            """)
    int creditNex(@Param("userId") Long userId,@Param("amount") BigDecimal amount,
                  @Param("sourceEnvironment") String sourceEnvironment,
                  @Param("expectedSandbox") int expectedSandbox);
    @Select("SELECT asset,bucket,COALESCE(SUM(amount-recovered_amount),0) amount FROM nx_earnings_release_entry WHERE user_id=#{userId} AND status='ACTIVE' AND is_deleted=0 GROUP BY asset,bucket")
    List<BucketAmount> buckets(@Param("userId") Long userId);
    @Select("SELECT COALESCE(SUM(amount-recovered_amount),0) FROM nx_earnings_release_entry WHERE user_id=#{userId} AND asset='USDT' AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0")
    BigDecimal protectedAmount(@Param("userId") Long userId);
    @Update("UPDATE nx_earnings_release_entry SET bucket='withdrawable',release_source=#{source},released_at=NOW(),updated_at=NOW() WHERE entry_no=#{entryNo} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0")
    int release(@Param("entryNo") String entryNo,@Param("source") String source);
    @Select("SELECT COALESCE(SUM(online_seconds),0) FROM nx_earnings_release_attestation WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment}")
    long attestedSeconds(@Param("userId") Long userId,@Param("sourceEnvironment") String sourceEnvironment);
    @Select("SELECT c.cluster_id clusterId,c.account_count accountCount,c.status FROM nx_admin_risk_multi_account_cluster c JOIN nx_user u ON u.id=#{userId} AND u.is_deleted=0 WHERE c.is_deleted=0 AND c.nodes_json IS NOT NULL AND JSON_VALID(c.nodes_json) AND JSON_CONTAINS(CAST(c.nodes_json AS JSON),JSON_OBJECT('userNo',CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0')))) ORDER BY c.account_count DESC,c.id ASC LIMIT 1")
    RiskCluster riskCluster(@Param("userId") Long userId);
    @Select("SELECT c.cluster_id clusterId,c.account_count accountCount,c.status FROM nx_admin_risk_multi_account_cluster c JOIN nx_user u ON u.id=#{userId} AND u.is_deleted=0 WHERE c.is_deleted=0 AND c.nodes_json IS NOT NULL AND JSON_VALID(c.nodes_json) AND JSON_CONTAINS(CAST(c.nodes_json AS JSON),JSON_OBJECT('userNo',CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0')))) ORDER BY c.account_count DESC,c.id ASC LIMIT 1 FOR UPDATE")
    RiskCluster lockRiskCluster(@Param("userId") Long userId);
    @Select("SELECT amount-recovered_amount AS amount FROM nx_earnings_release_entry WHERE user_id=#{userId} AND asset='USDT' AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0 ORDER BY id FOR UPDATE")
    List<BigDecimal> lockProtectedUsdtAmounts(@Param("userId") Long userId);
    @Select("SELECT entry_no entryNo,user_id userId,cluster_id clusterId,asset,amount-recovered_amount AS amount,bucket FROM nx_earnings_release_entry WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0 ORDER BY id")
    List<ProtectedEntry> protectedEntryScopes(@Param("userId") Long userId,@Param("sourceEnvironment") String sourceEnvironment);
    @Select("SELECT entry_no entryNo,user_id userId,cluster_id clusterId,asset,amount-recovered_amount AS amount,bucket FROM nx_earnings_release_entry WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0 ORDER BY id FOR UPDATE")
    List<ProtectedEntry> protectedEntries(@Param("userId") Long userId,@Param("sourceEnvironment") String sourceEnvironment);
    @Select("SELECT entry_no entryNo,user_id userId,cluster_id clusterId,asset,amount-recovered_amount AS amount,bucket FROM nx_earnings_release_entry WHERE entry_no=#{entryNo} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0 FOR UPDATE")
    ProtectedEntry lockProtectedEntry(@Param("entryNo") String entryNo);
    @Select("""
            <script>
            SELECT entry_no entryNo,user_id userId,cluster_id clusterId,source_type sourceType,
                   source_ref sourceRef,asset,amount-recovered_amount AS amount,bucket,created_at createdAt
              FROM nx_earnings_release_entry
             WHERE bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND amount>recovered_amount AND is_deleted=0
             <if test="userId != null">AND user_id=#{userId}</if>
             <if test="clusterId != null and clusterId != ''">AND cluster_id=#{clusterId}</if>
             ORDER BY id ASC LIMIT #{limit}
            </script>
            """)
    List<ProtectedEntryView> protectedEntryViews(@Param("userId") Long userId,
                                                 @Param("clusterId") String clusterId,
                                                 @Param("limit") int limit);
    @Select("SELECT cluster_id FROM nx_admin_risk_multi_account_cluster WHERE cluster_id=#{clusterId} AND is_deleted=0 FOR UPDATE")
    String lockCluster(@Param("clusterId") String clusterId);
    @Select("SELECT user_id FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 FOR UPDATE")
    Long lockUserScope(@Param("userId") Long userId);
    @Select("SELECT COUNT(DISTINCT user_id) FROM nx_earnings_release_entry WHERE cluster_id=#{clusterId} AND user_id<>#{userId} AND released_at>=DATE_SUB(NOW(),INTERVAL #{windowHours} HOUR) AND release_source='attest' AND status='ACTIVE' AND is_deleted=0")
    int releasedAccountsInWindow(@Param("clusterId") String clusterId,@Param("userId") Long userId,
                                 @Param("windowHours") int windowHours);
    record EntryWrite(String entryNo,Long userId,String clusterId,String sourceType,String sourceRef,String asset,
                      BigDecimal amount,String bucket,String idempotencyKey,String sourceEnvironment){}
    record ExistingEntry(String entryNo,Long userId,String sourceType,String sourceRef,String asset,BigDecimal amount,
                         String status,String idempotencyKey,String sourceEnvironment,Integer isDeleted){}
    record BucketAmount(String asset,String bucket,BigDecimal amount){}
    record ProtectedEntry(String entryNo,Long userId,String clusterId,String asset,BigDecimal amount,String bucket){}
    record ProtectedEntryView(String entryNo,Long userId,String clusterId,String sourceType,String sourceRef,
                              String asset,BigDecimal amount,String bucket,LocalDateTime createdAt){}
    record RiskCluster(String clusterId,Integer accountCount,String status){}
    record RecoveryWallet(Long walletId,BigDecimal usdtAvailable,BigDecimal nexAvailable,BigDecimal usdtDebited,BigDecimal nexDebited){}
    record RecoveryEntry(String entryNo,Long userId,String sourceType,String sourceRef,String asset,BigDecimal amount,
            String bucket,String status,String sourceEnvironment,Integer isDeleted,BigDecimal recoveredAmount,BigDecimal debitBaseline,Long sourceWalletId){}
    record RecoveryWrite(ffdd.opsconsole.finance.application.EarningsReleaseService.RewardRecoveryReceipt receipt,
            String sourceType,String sourceRef,String sourceEnvironment,String reason,String actor,
            BigDecimal balanceBefore,BigDecimal debitCounterBefore,BigDecimal sourceDebitBaseline){}
}
