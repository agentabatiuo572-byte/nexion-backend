package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FundsSandboxMapper extends BaseMapper<Object> {
    String ORDER_COLUMNS = """
            run_id runId,order_no orderNo,user_id userId,kind,channel,amount,target_address targetAddress,status,
            source,source_environment sourceEnvironment,idempotency_key idempotencyKey,
            request_hash requestHash,version,created_at createdAt,updated_at updatedAt,settled_at settledAt
            """;

    @Insert("""
            INSERT IGNORE INTO nx_funds_sandbox_wallet
              (run_id,user_id,available_usdt,reserved_usdt,version,created_at,updated_at,is_deleted)
            VALUES (#{runId},#{userId},0,0,0,NOW(),NOW(),0)
            """)
    int insertWalletIfAbsent(@Param("runId") String runId, @Param("userId") Long userId);

    /** Admission authority for every isolated funds fact: JWT identity alone is insufficient. */
    @Select("SELECT 1 FROM nx_user WHERE id=#{userId} AND is_deleted=0 AND status='ACTIVE' AND COALESCE(sandbox,0)=1 LIMIT 1")
    Integer isSandboxUser(@Param("userId") Long userId);

    @Select("""
            SELECT user_id userId,available_usdt availableUsdt,reserved_usdt reservedUsdt,version
              FROM nx_funds_sandbox_wallet
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    WalletRow lockWallet(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("""
            SELECT user_id userId,available_usdt availableUsdt,reserved_usdt reservedUsdt,version
              FROM nx_funds_sandbox_wallet
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 LIMIT 1
            """)
    WalletRow walletSnapshot(@Param("runId") String runId, @Param("userId") Long userId);

    @Update("""
            UPDATE nx_funds_sandbox_wallet
               SET available_usdt=available_usdt+#{amount},version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int creditWallet(@Param("runId") String runId, @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                     @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_funds_sandbox_wallet
               SET available_usdt=available_usdt-#{amount},version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{expectedVersion}
               AND available_usdt>=#{amount} AND is_deleted=0
            """)
    int debitWallet(@Param("runId") String runId, @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                    @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_funds_sandbox_wallet
               SET available_usdt=available_usdt-#{amount},reserved_usdt=reserved_usdt+#{amount},
                   version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{expectedVersion}
               AND available_usdt>=#{amount} AND is_deleted=0
            """)
    int reserveWallet(@Param("runId") String runId, @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                      @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_funds_sandbox_wallet
               SET reserved_usdt=reserved_usdt-#{amount},version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{expectedVersion}
               AND reserved_usdt>=#{amount} AND is_deleted=0
            """)
    int consumeReservedWallet(@Param("runId") String runId, @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                              @Param("expectedVersion") Long expectedVersion);

    @Update("""
            UPDATE nx_funds_sandbox_wallet
               SET available_usdt=available_usdt+#{amount},reserved_usdt=reserved_usdt-#{amount},
                   version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND user_id=#{userId} AND version=#{expectedVersion}
               AND reserved_usdt>=#{amount} AND is_deleted=0
            """)
    int releaseReservedWallet(@Param("runId") String runId, @Param("userId") Long userId, @Param("amount") BigDecimal amount,
                              @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_funds_sandbox_order
              (order_no,user_id,kind,channel,amount,target_address,status,source,source_environment,
               run_id,idempotency_key,request_hash,version,created_at,updated_at,is_deleted)
            VALUES
              (#{orderNo},#{userId},#{kind},#{channel},#{amount},#{targetAddress},#{status},#{source},
               #{sourceEnvironment},#{runId},#{idempotencyKey},#{requestHash},#{version},#{createdAt},#{createdAt},0)
            """)
    int insertOrder(OrderWrite order);

    @Select("SELECT " + ORDER_COLUMNS + " FROM nx_funds_sandbox_order"
            + " WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{idempotencyKey} AND is_deleted=0 LIMIT 1")
    OrderRow findOrderByIdempotency(@Param("runId") String runId, @Param("userId") Long userId,
                                    @Param("idempotencyKey") String idempotencyKey);

    /** Current read after the per-user wallet mutex; bypasses an older RR snapshot. */
    @Select("SELECT " + ORDER_COLUMNS + " FROM nx_funds_sandbox_order"
            + " WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{idempotencyKey} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    OrderRow lockOrderByIdempotency(@Param("runId") String runId, @Param("userId") Long userId,
                                    @Param("idempotencyKey") String idempotencyKey);

    /**
     * Locking current read used after the per-user wallet mutex. A plain COUNT
     * can stay on an older MySQL REPEATABLE READ snapshot when two withdrawals
     * race at the daily limit, so return up to the quota-sized set of durable
     * rows with FOR UPDATE instead.
     */
    @Select("""
            SELECT id
              FROM nx_funds_sandbox_order
             WHERE run_id=#{runId} AND user_id=#{userId} AND kind='WITHDRAWAL'
               AND status IN ('SUBMITTED','CONFIRMED') AND created_at>=#{since} AND is_deleted=0
             ORDER BY id
             LIMIT 10 FOR UPDATE
            """)
    List<Long> lockWithdrawalOrderIdsSince(@Param("runId") String runId, @Param("userId") Long userId,
                                           @Param("since") LocalDateTime since);

    @Select("SELECT " + ORDER_COLUMNS + " FROM nx_funds_sandbox_order"
            + " WHERE run_id=#{runId} AND user_id=#{userId} AND order_no=#{orderNo} AND is_deleted=0 LIMIT 1")
    OrderRow findOrderForUser(@Param("runId") String runId, @Param("userId") Long userId, @Param("orderNo") String orderNo);

    @Select("SELECT " + ORDER_COLUMNS + " FROM nx_funds_sandbox_order"
            + " WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit}")
    List<OrderRow> listOrders(@Param("runId") String runId, @Param("userId") Long userId, @Param("limit") int limit);

    @Update("""
            UPDATE nx_funds_sandbox_order
               SET status=#{nextStatus},version=version+1,settled_at=IF(#{nextStatus} IN ('SETTLED','CONFIRMED','FAILED'),NOW(),settled_at),updated_at=NOW()
             WHERE run_id=#{runId} AND order_no=#{orderNo} AND user_id=#{userId} AND status=#{expectedStatus}
               AND version=#{expectedVersion} AND is_deleted=0
            """)
    int transitionOrder(@Param("runId") String runId, @Param("orderNo") String orderNo, @Param("userId") Long userId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("nextStatus") String nextStatus,
                        @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_funds_sandbox_ledger
              (run_id,ledger_no,user_id,order_no,entry_role,direction,amount,available_after,reserved_after,
               source,source_environment,created_at,is_deleted)
            VALUES
              (#{runId},#{ledger.ledgerNo},#{ledger.userId},#{ledger.orderNo},#{ledger.entryRole},#{ledger.direction},#{ledger.amount},#{ledger.availableAfter},
               #{ledger.reservedAfter},'mock','SANDBOX',#{ledger.createdAt},0)
            """)
    int insertLedger(@Param("runId") String runId, @Param("ledger") LedgerWrite ledger);

    @Select("""
            SELECT run_id runId,ledger_no ledgerNo,user_id userId,order_no orderNo,entry_role entryRole,direction,
                   amount,available_after availableAfter,reserved_after reservedAfter,source,
                   source_environment sourceEnvironment,created_at createdAt
              FROM nx_funds_sandbox_ledger
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit}
            """)
    List<LedgerRow> listLedger(@Param("runId") String runId, @Param("userId") Long userId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO nx_funds_sandbox_callback_inbox
              (run_id,event_id,user_id,order_no,target_status,request_hash,process_status,source,
               source_environment,received_at,created_at,updated_at,is_deleted)
            VALUES
              (#{runId},#{callback.eventId},#{callback.userId},#{callback.orderNo},#{callback.targetStatus},#{callback.requestHash},'RECEIVED','mock',
               'SANDBOX',#{callback.receivedAt},#{callback.receivedAt},#{callback.receivedAt},0)
            """)
    int insertCallback(@Param("runId") String runId, @Param("callback") CallbackWrite callback);

    @Select("""
            SELECT run_id runId,event_id eventId,user_id userId,order_no orderNo,target_status targetStatus,
                   request_hash requestHash,process_status processStatus
              FROM nx_funds_sandbox_callback_inbox
             WHERE run_id=#{runId} AND event_id=#{eventId} AND is_deleted=0 LIMIT 1
            """)
    CallbackRow findCallback(@Param("runId") String runId, @Param("eventId") String eventId);

    /** Current read used after a unique-key collision to adopt the committed winner. */
    @Select("""
            SELECT run_id runId,event_id eventId,user_id userId,order_no orderNo,target_status targetStatus,
                   request_hash requestHash,process_status processStatus
              FROM nx_funds_sandbox_callback_inbox
             WHERE run_id=#{runId} AND event_id=#{eventId} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    CallbackRow lockCallback(@Param("runId") String runId, @Param("eventId") String eventId);

    @Update("""
            UPDATE nx_funds_sandbox_callback_inbox
               SET process_status=#{processStatus},processed_at=NOW(),updated_at=NOW()
             WHERE run_id=#{runId} AND event_id=#{eventId} AND process_status='RECEIVED' AND is_deleted=0
            """)
    int markCallbackProcessed(@Param("runId") String runId, @Param("eventId") String eventId,
                              @Param("processStatus") String processStatus);

    record WalletRow(Long userId, BigDecimal availableUsdt, BigDecimal reservedUsdt, Long version) { }
    record OrderRow(String runId, String orderNo, Long userId, String kind, String channel, BigDecimal amount,
                    String targetAddress, String status, String source, String sourceEnvironment,
                    String idempotencyKey, String requestHash, Long version,
                    LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime settledAt) { }
    record OrderWrite(String orderNo, Long userId, String kind, String channel, BigDecimal amount,
                      String targetAddress, String status, String source, String sourceEnvironment,
                      String runId, String idempotencyKey, String requestHash, Long version, LocalDateTime createdAt) { }
    record LedgerWrite(String ledgerNo, Long userId, String orderNo, String entryRole, String direction,
                       BigDecimal amount, BigDecimal availableAfter, BigDecimal reservedAfter,
                       LocalDateTime createdAt) { }
    record LedgerRow(String runId, String ledgerNo, Long userId, String orderNo, String entryRole, String direction,
                     BigDecimal amount, BigDecimal availableAfter, BigDecimal reservedAfter,
                     String source, String sourceEnvironment, LocalDateTime createdAt) { }
    record CallbackWrite(String eventId, Long userId, String orderNo, String targetStatus,
                         String requestHash, LocalDateTime receivedAt) { }
    record CallbackRow(String runId, String eventId, Long userId, String orderNo, String targetStatus,
                       String requestHash, String processStatus) { }
}
