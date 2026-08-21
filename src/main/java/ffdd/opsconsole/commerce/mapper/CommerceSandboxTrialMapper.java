package ffdd.opsconsole.commerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Run-scoped trial claims. This mapper deliberately has no nx_trial_claim access. */
@Mapper
public interface CommerceSandboxTrialMapper extends BaseMapper<Object> {
    @Select("""
            SELECT claim_no claimNo,user_id userId,product_no productNo,device_name deviceName,status,
                   claimed_at claimedAt,expires_at expiresAt,finished_at finishedAt,version,
                   shadow_daily_usdt shadowDailyUsdt,shadow_daily_nex shadowDailyNex,offset_cap_usdt offsetCapUsdt,
                   price_usdt priceUsdt,order_no orderNo,payment_no paymentNo,discount_usdt discountUsdt,amount_usdt amountUsdt
              FROM nx_commerce_sandbox_trial_claim
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 LIMIT 1
            """)
    TrialClaim find(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("""
            SELECT claim_no claimNo,user_id userId,product_no productNo,device_name deviceName,status,
                   claimed_at claimedAt,expires_at expiresAt,finished_at finishedAt,version,
                   shadow_daily_usdt shadowDailyUsdt,shadow_daily_nex shadowDailyNex,offset_cap_usdt offsetCapUsdt,
                   price_usdt priceUsdt,order_no orderNo,payment_no paymentNo,discount_usdt discountUsdt,amount_usdt amountUsdt
              FROM nx_commerce_sandbox_trial_claim
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    TrialClaim lock(@Param("runId") String runId, @Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_commerce_sandbox_trial_claim
              (run_id,user_id,claim_no,product_no,device_name,status,claimed_at,expires_at,finished_at,version,
               shadow_daily_usdt,shadow_daily_nex,offset_cap_usdt,price_usdt,order_no,payment_no,discount_usdt,amount_usdt,
               source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{runId},#{userId},#{claimNo},#{productNo},#{deviceName},'ACTIVE',#{claimedAt},#{expiresAt},NULL,0,
                    #{shadowDailyUsdt},#{shadowDailyNex},#{offsetCapUsdt},#{priceUsdt},NULL,NULL,NULL,NULL,
                    'mock','SANDBOX',NOW(),NOW(),0)
            """)
    int insertTrialClaim(TrialClaimWrite row);

    @Update("""
            UPDATE nx_commerce_sandbox_trial_claim
               SET status='REDEEMED',finished_at=#{finishedAt},version=version+1,order_no=#{orderNo},payment_no=#{paymentNo},
                   discount_usdt=#{discountUsdt},amount_usdt=#{amountUsdt},updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{version} AND status IN ('ACTIVE','GRACE') AND is_deleted=0
            """)
    int markConverted(@Param("runId") String runId, @Param("userId") Long userId, @Param("version") Long version,
                      @Param("finishedAt") LocalDateTime finishedAt, @Param("orderNo") String orderNo,
                      @Param("paymentNo") String paymentNo, @Param("discountUsdt") BigDecimal discountUsdt,
                      @Param("amountUsdt") BigDecimal amountUsdt);

    @Update("""
            UPDATE nx_commerce_sandbox_trial_claim
               SET status='CANCELLED',finished_at=#{finishedAt},version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{version} AND status='ACTIVE' AND is_deleted=0
            """)
    int cancel(@Param("runId") String runId, @Param("userId") Long userId, @Param("version") Long version,
               @Param("finishedAt") LocalDateTime finishedAt);

    record TrialClaimWrite(String runId, Long userId, String claimNo, String productNo, String deviceName,
                           LocalDateTime claimedAt, LocalDateTime expiresAt, BigDecimal shadowDailyUsdt,
                           BigDecimal shadowDailyNex, BigDecimal offsetCapUsdt, BigDecimal priceUsdt) { }

    record TrialClaim(String claimNo, Long userId, String productNo, String deviceName, String status,
                      LocalDateTime claimedAt, LocalDateTime expiresAt, LocalDateTime finishedAt, Long version,
                      BigDecimal shadowDailyUsdt, BigDecimal shadowDailyNex, BigDecimal offsetCapUsdt,
                      BigDecimal priceUsdt, String orderNo, String paymentNo, BigDecimal discountUsdt,
                      BigDecimal amountUsdt) { }
}
