package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL boundary for the four acceptance-only G2 fixture tables. */
@Mapper
public interface G2AcceptanceSandboxMapper extends BaseMapper<Object> {
    @Select("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('nx_g2_acceptance_sandbox_batch','nx_g2_acceptance_sandbox_order','nx_g2_acceptance_sandbox_ledger','nx_g2_acceptance_sandbox_idempotency')")
    int sandboxTableCount();

    @Insert("INSERT INTO nx_g2_acceptance_sandbox_batch (batch_no,source,source_environment,status,created_at,updated_at) VALUES (#{batchNo},#{source},#{sourceEnvironment},#{status},#{createdAt},#{updatedAt})")
    int insertBatch(BatchWrite row);

    @Insert("INSERT INTO nx_g2_acceptance_sandbox_order (exchange_no,batch_no,fixture_outcome,status,reason_code,reason,amount_usdt,source,source_environment,created_at,updated_at) VALUES (#{exchangeNo},#{batchNo},#{fixtureOutcome},#{status},#{reasonCode},#{reason},#{amountUsdt},#{source},#{sourceEnvironment},#{createdAt},#{updatedAt})")
    int insertOrder(OrderWrite row);

    @Select("SELECT batch_no FROM nx_g2_acceptance_sandbox_batch ORDER BY created_at DESC LIMIT 1")
    String latestBatchNo();

    @Select("SELECT COUNT(1) FROM nx_g2_acceptance_sandbox_batch WHERE batch_no=#{batchNo}")
    int batchCount(@Param("batchNo") String batchNo);

    @Select("SELECT batch_no FROM nx_g2_acceptance_sandbox_idempotency WHERE command_key=#{commandKey}")
    String idempotencyBatch(@Param("commandKey") String commandKey);

    @Select("SELECT exchange_no AS exchangeNo,fixture_outcome AS fixtureOutcome,amount_usdt AS amountUsdt FROM nx_g2_acceptance_sandbox_order WHERE batch_no=#{batchNo} AND status='QUEUED' ORDER BY exchange_no")
    List<QueuedRow> queuedRows(@Param("batchNo") String batchNo);

    @Update("UPDATE nx_g2_acceptance_sandbox_order SET status='COMPLETED',updated_at=#{updatedAt} WHERE exchange_no=#{exchangeNo} AND status='QUEUED'")
    int complete(@Param("exchangeNo") String exchangeNo, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE nx_g2_acceptance_sandbox_order SET status='SKIPPED',updated_at=#{updatedAt} WHERE exchange_no=#{exchangeNo} AND status='QUEUED'")
    int skip(@Param("exchangeNo") String exchangeNo, @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("INSERT INTO nx_g2_acceptance_sandbox_ledger (entry_no,batch_no,exchange_no,asset,direction,amount,source,source_environment,created_at) VALUES (#{entryNo},#{batchNo},#{exchangeNo},#{asset},#{direction},#{amount},#{source},#{sourceEnvironment},#{createdAt})")
    int insertLedger(LedgerWrite row);

    @Update("UPDATE nx_g2_acceptance_sandbox_batch SET status='PROCESSED',updated_at=#{updatedAt} WHERE batch_no=#{batchNo}")
    int markProcessed(@Param("batchNo") String batchNo, @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("INSERT INTO nx_g2_acceptance_sandbox_idempotency (command_key,batch_no,created_at) VALUES (#{commandKey},#{batchNo},#{createdAt})")
    int insertIdempotency(@Param("commandKey") String commandKey, @Param("batchNo") String batchNo, @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT batch_no AS batchNo,status,created_at AS createdAt,updated_at AS updatedAt FROM nx_g2_acceptance_sandbox_batch WHERE batch_no=#{batchNo}")
    Map<String, Object> batch(@Param("batchNo") String batchNo);

    @Select("SELECT exchange_no AS exchangeNo,status,reason_code AS reasonCode,reason,amount_usdt AS amountUsdt FROM nx_g2_acceptance_sandbox_order WHERE batch_no=#{batchNo} ORDER BY exchange_no")
    List<OrderRow> orders(@Param("batchNo") String batchNo);

    @Select("SELECT COUNT(1) FROM nx_g2_acceptance_sandbox_ledger WHERE exchange_no=#{exchangeNo}")
    int ledgerEntries(@Param("exchangeNo") String exchangeNo);

    @Select("SELECT COUNT(1) FROM nx_g2_acceptance_sandbox_ledger WHERE batch_no=#{batchNo}")
    int ledgerCount(@Param("batchNo") String batchNo);

    @Delete("DELETE FROM nx_g2_acceptance_sandbox_ledger WHERE batch_no=#{batchNo}")
    int deleteLedger(@Param("batchNo") String batchNo);
    @Delete("DELETE FROM nx_g2_acceptance_sandbox_idempotency WHERE batch_no=#{batchNo}")
    int deleteIdempotency(@Param("batchNo") String batchNo);
    @Delete("DELETE FROM nx_g2_acceptance_sandbox_order WHERE batch_no=#{batchNo}")
    int deleteOrders(@Param("batchNo") String batchNo);
    @Delete("DELETE FROM nx_g2_acceptance_sandbox_batch WHERE batch_no=#{batchNo}")
    int deleteBatch(@Param("batchNo") String batchNo);

    record BatchWrite(String batchNo, String source, String sourceEnvironment, String status, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    record OrderWrite(String exchangeNo, String batchNo, String fixtureOutcome, String status, String reasonCode, String reason, BigDecimal amountUsdt, String source, String sourceEnvironment, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    record LedgerWrite(String entryNo, String batchNo, String exchangeNo, String asset, String direction, BigDecimal amount, String source, String sourceEnvironment, LocalDateTime createdAt) { }
    record QueuedRow(String exchangeNo, String fixtureOutcome, BigDecimal amountUsdt) { }
    record OrderRow(String exchangeNo, String status, String reasonCode, String reason, BigDecimal amountUsdt) { }
}
