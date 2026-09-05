package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppWalletBillsMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox,UTC_TIMESTAMP(6) snapshotAt FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    /** Compatibility overload; the mapped statement remains the 3-argument paged query. */
    default List<LedgerRow> rows(Long userId, int limit) {
        return rows(userId, limit, 0);
    }

    @Select("""
            SELECT id,biz_no bizNo,biz_type bizType,UPPER(asset) asset,UPPER(direction) direction,
                   amount,balance_after balanceAfter,UPPER(status) status,remark,created_at createdAt
              FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<LedgerRow> rows(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(1) FROM nx_wallet_ledger WHERE user_id=#{userId} AND is_deleted=0")
    long count(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT id,biz_no bizNo,biz_type bizType,UPPER(asset) asset,UPPER(direction) direction,
                   amount,balance_after balanceAfter,UPPER(status) status,remark,created_at createdAt
              FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0
             <if test="asset != null">AND UPPER(asset)=#{asset}</if>
             <if test="direction != null">AND UPPER(direction)=#{direction} AND COALESCE(amount,0)&gt;0</if>
             <if test="category != null">
               AND UPPER(direction)='IN' AND COALESCE(amount,0)&gt;0
               AND CASE
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'DEPOSIT|TOPUP|RECHARGE' THEN 'topup'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'WITHDRAW|PAYOUT' THEN 'withdraw'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REFERRAL|COMMISSION|UNILEVEL|BINARY|LEADERSHIP' THEN 'refer'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'STAKE|STAKING' THEN IF(UPPER(direction)='IN','unstake','stake')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EXCHANGE|SWAP' THEN 'swap'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'ACHIEVEMENT|MILESTONE|QUEST' THEN 'achievement'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REWARD|BONUS' THEN 'reward'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'PURCHASE|ORDER|REPURCHASE|GENESIS|TRADE_IN' THEN IF(UPPER(direction)='IN','earn','purchase')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EARN|RELEASE|TASK|TRIAL' THEN 'earn'
                 ELSE 'other' END IN ('refer','achievement','reward')
             </if>
             ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LedgerRow> rowsFiltered(@Param("userId") Long userId, @Param("limit") int limit,
                                 @Param("offset") int offset, @Param("asset") String asset,
                                 @Param("direction") String direction, @Param("category") String category);

    @Select("""
            <script>
            SELECT COUNT(1) FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0
             <if test="asset != null">AND UPPER(asset)=#{asset}</if>
             <if test="direction != null">AND UPPER(direction)=#{direction} AND COALESCE(amount,0)&gt;0</if>
             <if test="category != null">
               AND UPPER(direction)='IN' AND COALESCE(amount,0)&gt;0
               AND CASE
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'DEPOSIT|TOPUP|RECHARGE' THEN 'topup'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'WITHDRAW|PAYOUT' THEN 'withdraw'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REFERRAL|COMMISSION|UNILEVEL|BINARY|LEADERSHIP' THEN 'refer'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'STAKE|STAKING' THEN IF(UPPER(direction)='IN','unstake','stake')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EXCHANGE|SWAP' THEN 'swap'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'ACHIEVEMENT|MILESTONE|QUEST' THEN 'achievement'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REWARD|BONUS' THEN 'reward'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'PURCHASE|ORDER|REPURCHASE|GENESIS|TRADE_IN' THEN IF(UPPER(direction)='IN','earn','purchase')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EARN|RELEASE|TASK|TRIAL' THEN 'earn'
                 ELSE 'other' END IN ('refer','achievement','reward')
             </if>
            </script>
            """)
    long countFiltered(@Param("userId") Long userId, @Param("asset") String asset,
                       @Param("direction") String direction, @Param("category") String category);

    @Select("""
            <script>
            SELECT id,biz_no bizNo,biz_type bizType,UPPER(asset) asset,UPPER(direction) direction,
                   amount,balance_after balanceAfter,UPPER(status) status,remark,created_at createdAt
              FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0
             <if test="asset != null">AND UPPER(asset)=#{asset}</if>
             <if test="direction != null">AND UPPER(direction)=#{direction} AND COALESCE(amount,0)&gt;0</if>
             <if test="category != null">
               AND UPPER(direction)='IN' AND COALESCE(amount,0)&gt;0
               AND CASE
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'DEPOSIT|TOPUP|RECHARGE' THEN 'topup'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'WITHDRAW|PAYOUT' THEN 'withdraw'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REFERRAL|COMMISSION|UNILEVEL|BINARY|LEADERSHIP' THEN 'refer'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'STAKE|STAKING' THEN IF(UPPER(direction)='IN','unstake','stake')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EXCHANGE|SWAP' THEN 'swap'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'ACHIEVEMENT|MILESTONE|QUEST' THEN 'achievement'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REWARD|BONUS' THEN 'reward'
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'PURCHASE|ORDER|REPURCHASE|GENESIS|TRADE_IN' THEN IF(UPPER(direction)='IN','earn','purchase')
                 WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EARN|RELEASE|TASK|TRIAL' THEN 'earn'
                 ELSE 'other' END IN ('refer','achievement','reward')
             </if>
             <if test="cursorCreatedAt != null">
               AND (created_at &lt; #{cursorCreatedAt} OR (created_at = #{cursorCreatedAt} AND id &lt; #{cursorId}))
             </if>
             ORDER BY created_at DESC,id DESC LIMIT #{limit}
            </script>
            """)
    List<LedgerRow> rowsAfter(@Param("userId") Long userId, @Param("limit") int limit,
                              @Param("asset") String asset, @Param("direction") String direction,
                              @Param("category") String category, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                              @Param("cursorId") Long cursorId);

    @Select("""
            SELECT /*+ NO_MERGE(scoped) */
              COALESCE(SUM(CASE WHEN bill_category IN ('refer','achievement','reward') AND UPPER(direction)='IN'
                AND COALESCE(amount,0)>0 AND UPPER(asset)='USDT' THEN GREATEST(COALESCE(amount,0),0) ELSE 0 END),0) rewardsUsdt,
              COALESCE(SUM(CASE WHEN bill_category IN ('refer','achievement','reward') AND UPPER(direction)='IN'
                AND COALESCE(amount,0)>0 AND UPPER(asset)='NEX' THEN GREATEST(COALESCE(amount,0),0) ELSE 0 END),0) rewardsNex,
              MAX(CASE WHEN bill_category IN ('refer','achievement','reward') AND UPPER(direction)='IN'
                AND COALESCE(amount,0)>0 THEN created_at END) latestRewardAt,
              COALESCE(SUM(CASE WHEN created_at >= #{dayStart} AND created_at < #{nextDay}
                AND UPPER(asset)='NEX' AND UPPER(status) IN ('SUCCESS','POSTED','COMPLETED','CONFIRMED','PENDING')
                AND bill_category='earn' THEN CASE WHEN UPPER(direction)='IN' THEN GREATEST(COALESCE(amount,0),0)
                          ELSE -GREATEST(COALESCE(amount,0),0) END ELSE 0 END),0) todayNexEarn,
              COALESCE(SUM(CASE WHEN UPPER(asset)='NEX' AND UPPER(status)='PENDING'
                THEN GREATEST(COALESCE(amount,0),0) ELSE 0 END),0) pendingNex,
              COALESCE(SUM(CASE WHEN created_at >= #{monthStart} AND created_at < #{nextMonth} THEN 1 ELSE 0 END),0) monthBillCount
              FROM (
                SELECT asset,direction,amount,status,created_at,
                  CASE
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'DEPOSIT|TOPUP|RECHARGE' THEN 'topup'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'WITHDRAW|PAYOUT' THEN 'withdraw'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REFERRAL|COMMISSION|UNILEVEL|BINARY|LEADERSHIP' THEN 'refer'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'STAKE|STAKING' THEN IF(UPPER(direction)='IN','unstake','stake')
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EXCHANGE|SWAP' THEN 'swap'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'ACHIEVEMENT|MILESTONE|QUEST' THEN 'achievement'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'REWARD|BONUS' THEN 'reward'
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'PURCHASE|ORDER|REPURCHASE|GENESIS|TRADE_IN' THEN IF(UPPER(direction)='IN','earn','purchase')
                    WHEN UPPER(COALESCE(biz_type,'')) REGEXP 'EARN|RELEASE|TASK|TRIAL' THEN 'earn'
                    ELSE 'other' END bill_category
                  FROM nx_wallet_ledger
                 WHERE user_id=#{userId} AND is_deleted=0
              ) scoped
            """)
    SummaryRow summary(@Param("userId") Long userId, @Param("dayStart") LocalDateTime dayStart,
                       @Param("nextDay") LocalDateTime nextDay, @Param("monthStart") LocalDateTime monthStart,
                       @Param("nextMonth") LocalDateTime nextMonth);

    @Select("""
            SELECT id,biz_no bizNo,biz_type bizType,UPPER(asset) asset,UPPER(direction) direction,
                   amount,balance_after balanceAfter,UPPER(status) status,remark,created_at createdAt
              FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0 AND UPPER(asset)='NEX'
               AND UPPER(status) NOT IN ('FAILED','REJECTED','CANCELLED')
             ORDER BY created_at DESC,id DESC LIMIT #{limit}
            """)
    List<LedgerRow> recentNexRows(@Param("userId") Long userId, @Param("limit") int limit);

    record UserScope(Integer sandbox, LocalDateTime snapshotAt) {
        public UserScope(Integer sandbox) {
            this(sandbox, null);
        }
    }
    record LedgerRow(Long id, String bizNo, String bizType, String asset, String direction,
                     BigDecimal amount, BigDecimal balanceAfter, String status, String remark,
                     LocalDateTime createdAt) { }
    record SummaryRow(BigDecimal rewardsUsdt, BigDecimal rewardsNex, LocalDateTime latestRewardAt,
                      BigDecimal todayNexEarn, BigDecimal pendingNex, Long monthBillCount) { }
}
