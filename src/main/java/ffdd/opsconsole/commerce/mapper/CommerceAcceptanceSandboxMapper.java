package ffdd.opsconsole.commerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Commerce-owned state only; FundsSandboxMapper owns all isolated wallet and ledger mutations. */
@Mapper
public interface CommerceAcceptanceSandboxMapper extends BaseMapper<Object> {
    @Select("SELECT COUNT(1) FROM nx_user WHERE id=#{userId} AND sandbox=1 AND is_deleted=0")
    boolean isSandboxUser(@Param("userId") Long userId);

    @Select("SELECT request_hash requestHash,result_json resultJson FROM nx_commerce_sandbox_order_receipt WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    SandboxOrderReceipt lockOrderReceipt(@Param("runId") String runId, @Param("userId") Long userId, @Param("key") String key);

    /** A plain read deliberately avoids an absent-key next-key lock before the unique INSERT claim. */
    @Select("SELECT request_hash requestHash,result_json resultJson FROM nx_commerce_sandbox_order_receipt WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} AND is_deleted=0 LIMIT 1")
    SandboxOrderReceipt findOrderReceipt(@Param("runId") String runId, @Param("userId") Long userId, @Param("key") String key);

    /**
     * The unique run/user/key receipt is acquired before the stock reservation.
     * INSERT IGNORE makes a concurrent contender wait for the first transaction,
     * then observe its completed immutable response instead of duplicating work.
     */
    @Insert("INSERT IGNORE INTO nx_commerce_sandbox_order_receipt(run_id,user_id,idempotency_key,request_hash,result_json,source,source_environment,created_at,is_deleted) VALUES(#{runId},#{userId},#{key},#{requestHash},#{resultJson},'mock','SANDBOX',NOW(),0)")
    int claimOrderReceipt(OrderReceiptWrite row);

    @Update("UPDATE nx_commerce_sandbox_order_receipt SET result_json=#{resultJson} WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} AND request_hash=#{requestHash} AND is_deleted=0")
    int completeOrderReceipt(OrderReceiptWrite row);

    @Delete("DELETE FROM nx_commerce_sandbox_order_receipt WHERE run_id=#{runId} AND user_id=#{userId} AND idempotency_key=#{key} AND request_hash=#{requestHash}")
    int releaseOrderReceiptClaim(OrderReceiptWrite row);

    @Insert("INSERT INTO nx_commerce_sandbox_audit(run_id,event_id,order_no,actor,reason,event,replay,canonical_status,result_version,wallet_after,source,source_environment,strict_profile,created_at) VALUES(#{runId},#{eventId},#{orderNo},#{actor},#{reason},#{event},#{replay},#{canonicalStatus},#{resultVersion},#{walletAfter},'mock','SANDBOX',1,NOW())")
    int insertAudit(SandboxAuditWrite row);

    /** The source is read only to populate a one-way isolated catalogue snapshot. */
    @Select("""
            SELECT id productId,product_no productNo,name,tier,price_usdt priceUsdt,stock,sold_count sold,
                   product_type deviceType,generation,gpu_model gpuModel,vram_total_gb vramTotalGb,hashrate,
                   estimated_daily_usdt dailyUsdt,daily_nex dailyNex,tagline,badge,unlock_phase unlockPhase
              FROM nx_product WHERE is_deleted=0 AND UPPER(status) IN ('ACTIVE','ON_SALE') AND store_visible=1
               AND stock>=1 AND price_usdt>0
            """)
    List<CatalogSeed> listEligibleCatalogSeeds();

    @Insert("""
            INSERT INTO nx_commerce_sandbox_catalog
              (product_id,product_no,name,tier,price_usdt,stock,sold_count,device_type,generation,gpu_model,vram_total_gb,hashrate,
               daily_usdt,daily_nex,tagline,badge,unlock_phase,run_id,version,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{productId},#{productNo},#{name},#{tier},#{priceUsdt},#{stock},#{sold},#{deviceType},#{generation},#{gpuModel},#{vramTotalGb},#{hashrate},
                    #{dailyUsdt},#{dailyNex},#{tagline},#{badge},#{unlockPhase},#{runId},0,'mock','SANDBOX',NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE
              product_no=VALUES(product_no),name=VALUES(name),tier=VALUES(tier),price_usdt=VALUES(price_usdt),
              stock=IF(version=0,VALUES(stock),stock),sold_count=IF(version=0,VALUES(sold_count),sold_count),
              device_type=VALUES(device_type),generation=VALUES(generation),gpu_model=VALUES(gpu_model),
              vram_total_gb=VALUES(vram_total_gb),hashrate=VALUES(hashrate),daily_usdt=VALUES(daily_usdt),daily_nex=VALUES(daily_nex),
              tagline=VALUES(tagline),badge=VALUES(badge),unlock_phase=VALUES(unlock_phase),updated_at=NOW(),is_deleted=0
            """)
    int upsertCatalog(CatalogSeed row);

    @Delete("""
            DELETE FROM nx_commerce_sandbox_catalog
             WHERE run_id=#{runId}
               AND NOT EXISTS (
                 SELECT 1 FROM nx_product p
                  WHERE p.id=nx_commerce_sandbox_catalog.product_id AND p.is_deleted=0 AND p.store_visible=1
                    AND UPPER(p.status) IN ('ACTIVE','ON_SALE') AND p.stock>=1 AND p.price_usdt>0
               )
               AND NOT EXISTS (
                 SELECT 1 FROM nx_commerce_sandbox_order o
                  WHERE o.run_id=nx_commerce_sandbox_catalog.run_id
                    AND o.product_id=nx_commerce_sandbox_catalog.product_id AND o.is_deleted=0
               )
            """)
    int pruneCatalog(@Param("runId") String runId);

    @Select("""
            SELECT c.product_id productId,p.product_no productNo,p.name,p.tier,p.price_usdt priceUsdt,c.stock,c.sold_count sold,
                   c.gpu_model gpuModel,c.vram_total_gb vramTotalGb,c.hashrate,c.daily_usdt dailyUsdt,c.daily_nex dailyNex,
                   c.tagline,c.badge,c.unlock_phase unlockPhase,c.version,c.updated_at updatedAt,
                   s.power_text AS power,s.features_json AS featuresJson,
                   s.ai_image_gen_per_min AS aiImageGenPerMin,s.ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
                   s.ai_video_min_per_hour AS aiVideoMinPerHour,s.ai_fine_tune_mins AS aiFineTuneMins,
                   s.ai_unlocks AS aiUnlocks,s.purchase_gate_json AS purchaseGateJson
              FROM nx_commerce_sandbox_catalog c
              JOIN nx_product p ON p.id=c.product_id AND p.product_no=c.product_no AND p.is_deleted=0 AND p.store_visible=1
                               AND UPPER(p.status) IN ('ACTIVE','ON_SALE') AND p.stock>=1 AND p.price_usdt>0
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=c.product_no AND s.is_deleted=0
             WHERE c.run_id=#{runId} AND c.is_deleted=0 AND c.stock>=1 AND c.price_usdt>0
               AND c.source='mock' AND c.source_environment='SANDBOX'
             ORDER BY c.product_id
            """)
    List<SandboxCatalogProduct> listSandboxCatalog(@Param("runId") String runId);

    @Select("""
            SELECT c.product_id productId,p.product_no productNo,p.name,p.tier,p.price_usdt priceUsdt,c.stock,c.sold_count sold,
                   c.gpu_model gpuModel,c.vram_total_gb vramTotalGb,c.hashrate,c.daily_usdt dailyUsdt,c.daily_nex dailyNex,
                   c.tagline,c.badge,c.unlock_phase unlockPhase,c.version,c.updated_at updatedAt,
                   s.power_text AS power,s.features_json AS featuresJson,
                   s.ai_image_gen_per_min AS aiImageGenPerMin,s.ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
                   s.ai_video_min_per_hour AS aiVideoMinPerHour,s.ai_fine_tune_mins AS aiFineTuneMins,
                   s.ai_unlocks AS aiUnlocks,s.purchase_gate_json AS purchaseGateJson
              FROM nx_commerce_sandbox_catalog c
              JOIN nx_product p ON p.id=c.product_id AND p.product_no=c.product_no AND p.is_deleted=0 AND p.store_visible=1
                               AND UPPER(p.status) IN ('ACTIVE','ON_SALE') AND p.stock>=#{quantity} AND p.price_usdt>0
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=c.product_no AND s.is_deleted=0
             WHERE c.run_id=#{runId} AND c.is_deleted=0 AND (c.product_id=#{productId} OR (#{productId} IS NULL AND c.product_no=#{productNo}))
               AND c.source='mock' AND c.source_environment='SANDBOX' LIMIT 1 FOR UPDATE
            """)
    SandboxCatalogProduct lockSandboxCatalogProduct(@Param("runId") String runId, @Param("productId") Long productId,
                                                     @Param("productNo") String productNo, @Param("quantity") Integer quantity);

    /** Refunds must be able to return isolated stock even after the canonical product was unlisted or deleted. */
    @Select("""
            SELECT c.product_id productId,c.product_no productNo,c.name,c.tier,c.price_usdt priceUsdt,c.stock,c.sold_count sold,
                   c.gpu_model gpuModel,c.vram_total_gb vramTotalGb,c.hashrate,c.daily_usdt dailyUsdt,c.daily_nex dailyNex,
                   c.tagline,c.badge,c.unlock_phase unlockPhase,c.version,c.updated_at updatedAt,
                   s.power_text AS power,s.features_json AS featuresJson,
                   s.ai_image_gen_per_min AS aiImageGenPerMin,s.ai_llm_tokens_per_sec AS aiLlmTokensPerSec,
                   s.ai_video_min_per_hour AS aiVideoMinPerHour,s.ai_fine_tune_mins AS aiFineTuneMins,
                   s.ai_unlocks AS aiUnlocks,s.purchase_gate_json AS purchaseGateJson
              FROM nx_commerce_sandbox_catalog c
              LEFT JOIN nx_admin_device_sku s ON s.sku_id=c.product_no AND s.is_deleted=0
             WHERE c.run_id=#{runId} AND c.product_id=#{productId} AND c.is_deleted=0
               AND c.source='mock' AND c.source_environment='SANDBOX' LIMIT 1 FOR UPDATE
            """)
    SandboxCatalogProduct lockSandboxCatalogProductForReturn(
            @Param("runId") String runId, @Param("productId") Long productId);

    @Update("""
            UPDATE nx_commerce_sandbox_catalog SET stock=stock-#{quantity},sold_count=sold_count+#{quantity},version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND product_id=#{productId} AND version=#{expectedVersion} AND stock>=#{quantity} AND is_deleted=0
               AND source='mock' AND source_environment='SANDBOX'
            """)
    int reserveSandboxCatalogStock(@Param("runId") String runId, @Param("productId") Long productId, @Param("expectedVersion") Long expectedVersion,
                                   @Param("quantity") Integer quantity);

    @Update("""
            UPDATE nx_commerce_sandbox_catalog SET stock=stock+#{quantity},sold_count=GREATEST(0,sold_count-#{quantity}),
                   version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND product_id=#{productId} AND version=#{expectedVersion} AND is_deleted=0
               AND source='mock' AND source_environment='SANDBOX'
            """)
    int returnSandboxCatalogStock(@Param("runId") String runId, @Param("productId") Long productId, @Param("expectedVersion") Long expectedVersion,
                                  @Param("quantity") Integer quantity);

    @Insert("""
            INSERT IGNORE INTO nx_commerce_sandbox_order
              (order_no,user_id,product_id,quantity,amount_usdt,canonical_revision,version,state,wallet_debited,stock_returned,
               run_id,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{orderNo},#{userId},#{productId},#{quantity},#{amountUsdt},#{canonicalRevision},0,'PENDING_PAYMENT',0,0,
                    #{runId},'mock','SANDBOX',NOW(),NOW(),0)
            """)
    int insertSandboxOrder(OrderWrite row);

    @Insert("""
            INSERT INTO nx_commerce_sandbox_inventory
              (order_no,product_id,product_no,unit_price_usdt,reserved_quantity,released_quantity,version,
               run_id,source,source_environment,created_at,updated_at,is_deleted)
            VALUES (#{orderNo},#{productId},#{productNo},#{unitPriceUsdt},#{reservedQuantity},0,0,
                    #{runId},'mock','SANDBOX',NOW(),NOW(),0)
            """)
    int insertInventory(InventoryWrite row);

    @Select("""
            SELECT order_no orderNo,user_id userId,product_id productId,quantity,amount_usdt amountUsdt,
                   version,state,wallet_debited walletDebited,stock_returned stockReturned
              FROM nx_commerce_sandbox_order
             WHERE run_id=#{runId} AND order_no=#{orderNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    SandboxOrder lockSandboxOrder(@Param("runId") String runId, @Param("orderNo") String orderNo);

    @Select("""
            SELECT order_no orderNo,product_id productId,product_no productNo,unit_price_usdt unitPriceUsdt,
                   reserved_quantity reservedQuantity,released_quantity releasedQuantity,version
              FROM nx_commerce_sandbox_inventory
             WHERE run_id=#{runId} AND order_no=#{orderNo} AND is_deleted=0 LIMIT 1 FOR UPDATE
            """)
    InventoryRow lockInventoryForOrder(@Param("runId") String runId, @Param("orderNo") String orderNo);

    @Update("""
            UPDATE nx_commerce_sandbox_order
               SET version=version+1,state=#{nextState},wallet_debited=#{walletDebited},stock_returned=#{stockReturned},updated_at=NOW()
             WHERE run_id=#{runId} AND order_no=#{orderNo} AND version=#{expectedVersion} AND state=#{expectedState} AND is_deleted=0
            """)
    int transitionSandboxOrder(@Param("runId") String runId, @Param("orderNo") String orderNo, @Param("expectedVersion") Long expectedVersion,
                               @Param("expectedState") String expectedState, @Param("nextState") String nextState,
                               @Param("walletDebited") boolean walletDebited, @Param("stockReturned") boolean stockReturned);

    @Update("""
            UPDATE nx_commerce_sandbox_inventory
               SET released_quantity=reserved_quantity,version=version+1,updated_at=NOW()
             WHERE run_id=#{runId} AND order_no=#{orderNo} AND version=#{expectedVersion} AND released_quantity=0
               AND reserved_quantity > 0 AND is_deleted=0
            """)
    int releaseInventory(@Param("runId") String runId, @Param("orderNo") String orderNo, @Param("expectedVersion") Long expectedVersion);

    @Insert("""
            INSERT INTO nx_commerce_sandbox_callback_inbox
              (event_id,order_no,target_status,expected_version,request_hash,canonical_status,result_version,wallet_after,run_id,
               source,source_environment,received_at,created_at,is_deleted)
            VALUES (#{eventId},#{orderNo},#{targetStatus},#{expectedVersion},#{requestHash},#{canonicalStatus},#{resultVersion},#{walletAfter},#{runId},
                    'mock','SANDBOX',#{receivedAt},#{receivedAt},0)
            """)
    int insertCallback(CallbackWrite row);

    @Select("SELECT event_id eventId,order_no orderNo,target_status targetStatus,expected_version expectedVersion,request_hash requestHash,canonical_status canonicalStatus,result_version resultVersion,wallet_after walletAfter FROM nx_commerce_sandbox_callback_inbox WHERE run_id=#{runId} AND event_id=#{eventId} AND is_deleted=0 LIMIT 1")
    Callback findCallback(@Param("runId") String runId, @Param("eventId") String eventId);

    /** Locking reads bypass an REPEATABLE_READ snapshot after a sibling callback released the order lock. */
    @Select("SELECT event_id eventId,order_no orderNo,target_status targetStatus,expected_version expectedVersion,request_hash requestHash,canonical_status canonicalStatus,result_version resultVersion,wallet_after walletAfter FROM nx_commerce_sandbox_callback_inbox WHERE run_id=#{runId} AND event_id=#{eventId} AND is_deleted=0 LIMIT 1 FOR UPDATE")
    Callback lockCurrentCallback(@Param("runId") String runId, @Param("eventId") String eventId);

    @Select("""
            SELECT order_no orderNo,state,updated_at updatedAt
              FROM nx_commerce_sandbox_order
             WHERE run_id=#{runId} AND user_id=#{userId} AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX'
            """)
    List<OrderOverlay> listOrderOverlays(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("""
            SELECT o.order_no orderNo,o.product_id productId,i.product_no productNo,o.quantity,i.unit_price_usdt unitPriceUsdt,
                   o.amount_usdt amountUsdt,o.state,o.version,o.created_at createdAt,o.updated_at updatedAt
              FROM nx_commerce_sandbox_order o JOIN nx_commerce_sandbox_inventory i ON i.order_no=o.order_no AND i.run_id=o.run_id AND i.is_deleted=0
             WHERE o.run_id=#{runId} AND o.user_id=#{userId} AND o.is_deleted=0 AND o.source='mock' AND o.source_environment='SANDBOX'
             ORDER BY o.created_at DESC
            """)
    List<SandboxOrderView> listSandboxOrders(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("""
            SELECT o.order_no orderNo,o.product_id productId,i.product_no productNo,o.quantity,i.unit_price_usdt unitPriceUsdt,
                   o.amount_usdt amountUsdt,o.state,o.version,o.created_at createdAt,o.updated_at updatedAt
              FROM nx_commerce_sandbox_order o JOIN nx_commerce_sandbox_inventory i ON i.order_no=o.order_no AND i.run_id=o.run_id AND i.is_deleted=0
             WHERE o.run_id=#{runId} AND o.is_deleted=0 AND o.source='mock' AND o.source_environment='SANDBOX' ORDER BY o.created_at DESC LIMIT #{limit}
            """)
    List<SandboxOrderView> listAllSandboxOrders(@Param("runId") String runId, @Param("limit") int limit);

    record CatalogSeed(Long productId, String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock, Integer sold,
                       String deviceType, String generation, String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                       BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline, String badge, String unlockPhase, String runId) {
        public CatalogSeed(Long productId, String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock, Integer sold,
                           String deviceType, String generation, String gpuModel, Integer vramTotalGb, BigDecimal hashrate,
                           BigDecimal dailyUsdt, BigDecimal dailyNex, String tagline, String badge, String unlockPhase) {
            this(productId, productNo, name, tier, priceUsdt, stock, sold, deviceType, generation, gpuModel, vramTotalGb, hashrate,
                    dailyUsdt, dailyNex, tagline, badge, unlockPhase, "test-run-0001");
        }
    }
    record SandboxCatalogProduct(Long productId, String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                 Integer sold, String gpuModel, Integer vramTotalGb, BigDecimal hashrate, BigDecimal dailyUsdt,
                                 BigDecimal dailyNex, String tagline, String badge, String unlockPhase, Long version,
                                 LocalDateTime updatedAt, String power, String featuresJson,
                                 BigDecimal aiImageGenPerMin, BigDecimal aiLlmTokensPerSec,
                                 BigDecimal aiVideoMinPerHour, BigDecimal aiFineTuneMins,
                                 String aiUnlocks, String purchaseGateJson) {
        public SandboxCatalogProduct(Long productId, String productNo, String name, String tier, BigDecimal priceUsdt, Integer stock,
                                     Integer sold, String gpuModel, Integer vramTotalGb, BigDecimal hashrate, BigDecimal dailyUsdt,
                                     BigDecimal dailyNex, String tagline, String badge, String unlockPhase, Long version,
                                     LocalDateTime updatedAt) {
            this(productId, productNo, name, tier, priceUsdt, stock, sold, gpuModel, vramTotalGb, hashrate,
                    dailyUsdt, dailyNex, tagline, badge, unlockPhase, version, updatedAt,
                    null, null, null, null, null, null, null, null);
        }
    }
    record OrderWrite(String orderNo, Long userId, Long productId, Integer quantity, BigDecimal amountUsdt,
                      Long canonicalRevision, String runId) {
        public OrderWrite(String orderNo, Long userId, Long productId, Integer quantity, BigDecimal amountUsdt, Long canonicalRevision) {
            this(orderNo, userId, productId, quantity, amountUsdt, canonicalRevision, "test-run-0001");
        }
    }
    record InventoryWrite(String orderNo, Long productId, String productNo, BigDecimal unitPriceUsdt,
                          Integer reservedQuantity, String runId) {
        public InventoryWrite(String orderNo, Long productId, String productNo, BigDecimal unitPriceUsdt, Integer reservedQuantity) {
            this(orderNo, productId, productNo, unitPriceUsdt, reservedQuantity, "test-run-0001");
        }
    }
    record SandboxOrder(String orderNo, Long userId, Long productId, Integer quantity, BigDecimal amountUsdt,
                        Long version, String state, boolean walletDebited, boolean stockReturned) { }
    record InventoryRow(String orderNo, Long productId, String productNo, BigDecimal unitPriceUsdt,
                        Integer reservedQuantity, Integer releasedQuantity, Long version) { }
    record CallbackWrite(String eventId, String orderNo, String targetStatus, Long expectedVersion,
                         String requestHash, String canonicalStatus, Long resultVersion, BigDecimal walletAfter,
                         LocalDateTime receivedAt, String runId) {
        public CallbackWrite(String eventId, String orderNo, String targetStatus, Long expectedVersion, String requestHash,
                             String canonicalStatus, Long resultVersion, BigDecimal walletAfter, LocalDateTime receivedAt) {
            this(eventId, orderNo, targetStatus, expectedVersion, requestHash, canonicalStatus, resultVersion, walletAfter,
                    receivedAt, "test-run-0001");
        }
    }
    record Callback(String eventId, String orderNo, String targetStatus, Long expectedVersion, String requestHash,
                    String canonicalStatus, Long resultVersion, BigDecimal walletAfter) { }
    record OrderOverlay(String orderNo, String state, LocalDateTime updatedAt) { }
    record SandboxOrderReceipt(String requestHash, String resultJson) { }
    record OrderReceiptWrite(String runId, Long userId, String key, String requestHash, String resultJson) { }
    record SandboxAuditWrite(String runId, String eventId, String orderNo, String actor, String reason,
                            String event, boolean replay, String canonicalStatus, Long resultVersion, BigDecimal walletAfter) { }
    record SandboxOrderView(String orderNo, Long productId, String productNo, Integer quantity, BigDecimal unitPriceUsdt,
                            BigDecimal amountUsdt, String state, Long version, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
