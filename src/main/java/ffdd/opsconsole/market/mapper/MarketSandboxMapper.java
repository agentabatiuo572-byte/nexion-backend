package ffdd.opsconsole.market.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MySQL-only persistence boundary for G1/G7 sandbox facts. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface MarketSandboxMapper {
    @Insert("""
            INSERT IGNORE INTO nx_market_sandbox_account
              (domain_key,run_id,user_id,wallet_usdt,version,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{domain},#{runId},#{userId},1000.000000,0,'mock','SANDBOX',NOW(),NOW(),0)
            """)
    int insertAccountIfAbsent(@Param("domain") String domain, @Param("runId") String runId,
                              @Param("userId") Long userId);

    @Select("""
            SELECT wallet_usdt AS walletUsdt,version
              FROM nx_market_sandbox_account
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    AccountRow lockAccount(@Param("domain") String domain, @Param("runId") String runId,
                           @Param("userId") Long userId);

    @Select("""
            SELECT wallet_usdt AS walletUsdt,version
              FROM nx_market_sandbox_account
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
             LIMIT 1
            """)
    AccountRow account(@Param("domain") String domain, @Param("runId") String runId,
                       @Param("userId") Long userId);

    @Update("""
            UPDATE nx_market_sandbox_account
               SET wallet_usdt=wallet_usdt+#{delta},version=version+1,updated_at=NOW()
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND version=#{expectedVersion} AND wallet_usdt+#{delta}>=0
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
            """)
    int updateWallet(@Param("domain") String domain, @Param("runId") String runId,
                     @Param("userId") Long userId, @Param("expectedVersion") Long expectedVersion,
                     @Param("delta") BigDecimal delta);

    @Insert("""
            INSERT INTO nx_market_sandbox_position
              (domain_key,run_id,user_id,position_no,product_code,product_name,amount_usdt,apy_pct,penalty_pct,
               term_days,locked_at,unlock_at,interest_usdt,status,version,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{domain},#{runId},#{userId},#{positionNo},#{productCode},#{productName},#{amountUsdt},#{apyPct},#{penaltyPct},
                    #{termDays},#{lockedAt},#{unlockAt},#{interestUsdt},'ACTIVE',0,'mock','SANDBOX',NOW(),NOW(),0)
            """)
    int insertPosition(PositionWrite row);

    @Select("""
            SELECT id,position_no AS positionNo,product_code AS productCode,product_name AS productName,
                   amount_usdt AS amountUsdt,apy_pct AS apyPct,penalty_pct AS penaltyPct,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,interest_usdt AS interestUsdt,status,version
              FROM nx_market_sandbox_position
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
             ORDER BY created_at DESC,id DESC
            """)
    List<PositionRow> listPositions(@Param("domain") String domain, @Param("runId") String runId,
                                    @Param("userId") Long userId);

    @Select("""
            SELECT id,position_no AS positionNo,product_code AS productCode,product_name AS productName,
                   amount_usdt AS amountUsdt,apy_pct AS apyPct,penalty_pct AS penaltyPct,term_days AS termDays,
                   locked_at AS lockedAt,unlock_at AS unlockAt,interest_usdt AS interestUsdt,status,version
              FROM nx_market_sandbox_position
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId} AND position_no=#{positionNo}
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    PositionRow lockPosition(@Param("domain") String domain, @Param("runId") String runId,
                              @Param("userId") Long userId, @Param("positionNo") String positionNo);

    @Update("""
            UPDATE nx_market_sandbox_position
               SET status=#{nextStatus},version=version+1,updated_at=NOW()
             WHERE id=#{id} AND domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND version=#{expectedVersion} AND status=#{expectedStatus}
               AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
            """)
    int transitionPosition(@Param("id") Long id, @Param("domain") String domain, @Param("runId") String runId,
                           @Param("userId") Long userId, @Param("expectedVersion") Long expectedVersion,
                           @Param("expectedStatus") String expectedStatus, @Param("nextStatus") String nextStatus);

    @Update("""
            UPDATE nx_market_sandbox_position SET status='MATURE_UNCLAIMED',version=version+1,updated_at=NOW()
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND status='ACTIVE' AND unlock_at<=#{now} AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0
            """)
    int maturePositions(@Param("domain") String domain, @Param("runId") String runId,
                        @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT request_hash AS requestHash,resource_no AS resourceNo
              FROM nx_market_sandbox_idempotency
             WHERE domain_key=#{domain} AND run_id=#{runId} AND user_id=#{userId}
               AND operation=#{operation} AND idempotency_key=#{idempotencyKey}
               AND source='mock' AND source_environment='SANDBOX'
             LIMIT 1
            """)
    IdempotencyRow findIdempotency(@Param("domain") String domain, @Param("runId") String runId,
                                   @Param("userId") Long userId, @Param("operation") String operation,
                                   @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO nx_market_sandbox_idempotency
              (domain_key,run_id,user_id,operation,idempotency_key,request_hash,resource_no,source,source_environment,created_at)
            VALUES (#{domain},#{runId},#{userId},#{operation},#{idempotencyKey},#{requestHash},#{resourceNo},'mock','SANDBOX',NOW())
            """)
    int insertIdempotency(IdempotencyWrite row);

    record AccountRow(BigDecimal walletUsdt, Long version) { }
    record PositionRow(Long id, String positionNo, String productCode, String productName, BigDecimal amountUsdt,
                       BigDecimal apyPct, BigDecimal penaltyPct, Integer termDays, LocalDateTime lockedAt,
                       LocalDateTime unlockAt, BigDecimal interestUsdt, String status, Long version) { }
    record PositionWrite(String domain, String runId, Long userId, String positionNo, String productCode,
                         String productName, BigDecimal amountUsdt, BigDecimal apyPct, BigDecimal penaltyPct,
                         Integer termDays, LocalDateTime lockedAt, LocalDateTime unlockAt, BigDecimal interestUsdt) { }
    record IdempotencyRow(String requestHash, String resourceNo) { }
    record IdempotencyWrite(String domain, String runId, Long userId, String operation, String idempotencyKey,
                            String requestHash, String resourceNo) { }
}
