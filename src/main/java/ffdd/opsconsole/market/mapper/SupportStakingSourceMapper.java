package ffdd.opsconsole.market.mapper;

import java.util.*;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** Fixed plain scoped SELECTs, retaining contradictory/deleted originals for rejection. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Cross-table original-source projection has no entity CRUD surface.
public interface SupportStakingSourceMapper {
    String POSITION="SELECT p.id,p.user_id AS customerId,p.position_no AS positionNo,p.product_id AS productId,p.product_code AS productCode,p.product_name AS productName,p.amount_usdt AS amountUsdt,p.apy_bps AS apyBps,p.early_penalty_bps AS earlyPenaltyBps,p.term_days AS termDays,p.locked_at AS lockedAt,p.unlock_at AS unlockAt,p.estimated_interest_usdt AS estimatedInterestUsdt,p.status,p.claimed_at AS claimedAt,p.early_withdrawn_at AS earlyWithdrawnAt,p.created_at AS createdAt,p.updated_at AS updatedAt,p.is_deleted AS deleted,u.sandbox AS userSandbox,u.is_deleted AS userDeleted FROM nx_staking_position p LEFT JOIN nx_user u ON u.id=p.user_id ";
    @Select("<script>"+POSITION+"WHERE p.user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY p.id</script>")
    @Results(id="supportStakingPosition",value={@Result(column="lockedAt",property="lockedAt",javaType=LocalDateTime.class),@Result(column="unlockAt",property="unlockAt",javaType=LocalDateTime.class),@Result(column="claimedAt",property="claimedAt",javaType=LocalDateTime.class),@Result(column="earlyWithdrawnAt",property="earlyWithdrawnAt",javaType=LocalDateTime.class),@Result(column="createdAt",property="createdAt",javaType=LocalDateTime.class),@Result(column="updatedAt",property="updatedAt",javaType=LocalDateTime.class)})
    List<Map<String,Object>> byCustomers(@Param("ids") Collection<Long> ids);
    @Select("<script>"+POSITION+"WHERE p.position_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY p.id</script>")
    @ResultMap("supportStakingPosition")
    List<Map<String,Object>> byPositionNos(@Param("ids") Collection<String> ids);
    @Select("<script>SELECT l.id,l.user_id AS customerId,p.position_no AS positionNo,l.biz_no AS bizNo,l.biz_type AS bizType,l.asset,l.direction,l.amount,l.balance_after AS balanceAfter,l.status,l.created_at AS createdAt,l.updated_at AS updatedAt,l.is_deleted AS deleted FROM nx_wallet_ledger l JOIN nx_staking_position p ON l.biz_no IN (CONCAT(p.position_no,'-OPEN'),CONCAT(p.position_no,'-CLAIM'),CONCAT(p.position_no,'-EARLY')) WHERE p.position_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY l.id</script>")
    List<Map<String,Object>> ledgers(@Param("ids") Collection<String> ids);
    @Select("SELECT id,user_id AS customerId,fund_incarnation AS incarnation,fund_revision AS revision,version,usdt_available AS usdtAvailable,nex_available AS nexAvailable,cregis_risk_held AS riskHeld,pending_withdraw AS pendingWithdrawal,sandbox,is_deleted AS deleted FROM nx_user_wallet WHERE user_id=#{id}")
    List<Map<String,Object>> wallets(@Param("id") long id);
    @Select("SELECT table_name AS tableName,column_name AS columnName,datetime_precision AS precision FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='nx_staking_position' AND column_name IN ('created_at','claimed_at','early_withdrawn_at')")
    List<Map<String,Object>> timePrecisions();
}
