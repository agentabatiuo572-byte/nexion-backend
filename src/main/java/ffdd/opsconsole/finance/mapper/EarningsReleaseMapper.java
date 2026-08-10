package ffdd.opsconsole.finance.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface EarningsReleaseMapper {
    @Insert("INSERT INTO nx_earnings_release_entry(entry_no,user_id,cluster_id,source_type,source_ref,asset,amount,bucket,status,idempotency_key,is_deleted) VALUES(#{entryNo},#{userId},#{clusterId},#{sourceType},#{sourceRef},#{asset},#{amount},#{bucket},'ACTIVE',#{idempotencyKey},0)")
    int insert(EntryWrite write);
    @Update("UPDATE nx_user_wallet SET usdt_available=usdt_available+#{amount},lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=NOW() WHERE user_id=#{userId} AND is_deleted=0")
    int creditUsdt(@Param("userId") Long userId,@Param("amount") BigDecimal amount);
    @Update("UPDATE nx_user_wallet SET nex_available=nex_available+#{amount},lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=NOW() WHERE user_id=#{userId} AND is_deleted=0")
    int creditNex(@Param("userId") Long userId,@Param("amount") BigDecimal amount);
    @Select("SELECT asset,bucket,COALESCE(SUM(amount),0) amount FROM nx_earnings_release_entry WHERE user_id=#{userId} AND status='ACTIVE' AND is_deleted=0 GROUP BY asset,bucket")
    List<BucketAmount> buckets(@Param("userId") Long userId);
    @Select("SELECT COALESCE(SUM(amount),0) FROM nx_earnings_release_entry WHERE user_id=#{userId} AND asset='USDT' AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0")
    BigDecimal protectedAmount(@Param("userId") Long userId);
    @Update("UPDATE nx_earnings_release_entry SET bucket='withdrawable',release_source=#{source},released_at=NOW(),updated_at=NOW() WHERE entry_no=#{entryNo} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0")
    int release(@Param("entryNo") String entryNo,@Param("source") String source);
    @Insert("INSERT INTO nx_earnings_release_attestation(user_id,device_id,first_seen_at,last_seen_at,online_seconds) VALUES(#{userId},#{deviceId},NOW(),NOW(),0) ON DUPLICATE KEY UPDATE online_seconds=online_seconds+LEAST(300,GREATEST(0,TIMESTAMPDIFF(SECOND,last_seen_at,NOW()))),last_seen_at=NOW(),updated_at=NOW()")
    int recordAttestation(@Param("userId") Long userId,@Param("deviceId") String deviceId);
    @Select("SELECT COALESCE(SUM(online_seconds),0) FROM nx_earnings_release_attestation WHERE user_id=#{userId}")
    long attestedSeconds(@Param("userId") Long userId);
    @Select("SELECT c.cluster_id clusterId,c.account_count accountCount,c.status FROM nx_admin_risk_multi_account_cluster c JOIN nx_user u ON u.id=#{userId} AND u.is_deleted=0 WHERE c.is_deleted=0 AND c.nodes_json IS NOT NULL AND JSON_VALID(c.nodes_json) AND JSON_CONTAINS(CAST(c.nodes_json AS JSON),JSON_OBJECT('userNo',CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0')))) ORDER BY c.account_count DESC,c.id ASC LIMIT 1")
    RiskCluster riskCluster(@Param("userId") Long userId);
    @Select("SELECT entry_no entryNo,user_id userId,cluster_id clusterId,asset,amount,bucket FROM nx_earnings_release_entry WHERE user_id=#{userId} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0 ORDER BY id FOR UPDATE")
    List<ProtectedEntry> protectedEntries(@Param("userId") Long userId);
    @Select("SELECT entry_no entryNo,user_id userId,cluster_id clusterId,asset,amount,bucket FROM nx_earnings_release_entry WHERE entry_no=#{entryNo} AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    ProtectedEntry lockProtectedEntry(@Param("entryNo") String entryNo);
    @Select("""
            <script>
            SELECT entry_no entryNo,user_id userId,cluster_id clusterId,source_type sourceType,
                   source_ref sourceRef,asset,amount,bucket,created_at createdAt
              FROM nx_earnings_release_entry
             WHERE bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0
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
    @Select("SELECT COUNT(1) FROM nx_janus_device WHERE user_id=#{userId} AND device_id=#{deviceId} AND is_deleted=0")
    int trustedDeviceBinding(@Param("userId") Long userId,@Param("deviceId") String deviceId);
    @Select("SELECT COUNT(DISTINCT user_id) FROM nx_earnings_release_entry WHERE cluster_id=#{clusterId} AND user_id<>#{userId} AND released_at>=DATE_SUB(NOW(),INTERVAL #{windowHours} HOUR) AND release_source='attest' AND status='ACTIVE' AND is_deleted=0")
    int releasedAccountsInWindow(@Param("clusterId") String clusterId,@Param("userId") Long userId,
                                 @Param("windowHours") int windowHours);
    record EntryWrite(String entryNo,Long userId,String clusterId,String sourceType,String sourceRef,String asset,BigDecimal amount,String bucket,String idempotencyKey){}
    record BucketAmount(String asset,String bucket,BigDecimal amount){}
    record ProtectedEntry(String entryNo,Long userId,String clusterId,String asset,BigDecimal amount,String bucket){}
    record ProtectedEntryView(String entryNo,Long userId,String clusterId,String sourceType,String sourceRef,
                              String asset,BigDecimal amount,String bucket,LocalDateTime createdAt){}
    record RiskCluster(String clusterId,Integer accountCount,String status){}
}
