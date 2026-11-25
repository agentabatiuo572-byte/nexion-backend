package ffdd.opsconsole.growth.mapper;

import ffdd.opsconsole.shared.canonical.StorefrontProductPublishGate;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TrialPurchaseOptionsMapper {
    String PRODUCT = """
            SELECT p.id,p.product_no productNo,p.name,p.product_type productType,p.inventory_mode inventoryMode,
                   p.price_usdt priceUsdt,p.stock,p.status,p.store_visible storeVisible,p.unlock_phase unlockPhase,
                   p.gpu_model gpuModel,p.vram_total_gb vramTotalGb,s.power_text power,
            """ + StorefrontProductPublishGate.BLOCK_REASON_SQL + """
             AS publishBlock
              FROM nx_product p LEFT JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
             WHERE p.is_deleted=0
            """;

    @Select(PRODUCT + " ORDER BY p.product_no")
    List<Product> products();

    @Select(PRODUCT + " AND p.product_no=#{no}")
    Product product(@Param("no") String no);

    @Select("SELECT version,product_nos productNos FROM nx_trial_purchase_options WHERE id=1")
    Options options();

    @Select("SELECT version,product_nos productNos FROM nx_trial_purchase_options WHERE id=1 FOR UPDATE")
    Options lockOptions();

    @Select("SELECT version,product_nos productNos FROM nx_trial_purchase_options WHERE id=1 FOR SHARE")
    Options shareOptions();

    @Update("UPDATE nx_trial_purchase_options SET version=version+1,product_nos=#{products} WHERE id=1")
    int replace(@Param("products") String products);

    @Select("""
            SELECT actor_id actorId,request_json requestJson,receipt_json receiptJson
              FROM nx_trial_purchase_options_command WHERE command_key=#{key}
            """)
    Command command(@Param("key") String key);

    @Insert("""
            INSERT INTO nx_trial_purchase_options_command(command_key,actor_id,request_json,receipt_json)
            VALUES (#{key},#{actor},#{request},#{receipt})
            """)
    int record(@Param("key") String key, @Param("actor") long actor,
               @Param("request") String request, @Param("receipt") String receipt);

    @Select("""
            SELECT b.snapshot_json FROM nx_trial_balance_submission b
              JOIN nx_payment_command c ON c.user_id=b.user_id AND c.command_key=b.command_key AND c.kind='trial-convert'
             WHERE b.user_id=#{owner} AND b.claim_no=#{claim} AND c.response_json IS NULL
             LIMIT 1
            """)
    String pendingBalance(@Param("owner") long owner, @Param("claim") String claim);

    @Select("SELECT claim_no FROM nx_trial_balance_submission WHERE user_id=#{owner} AND command_key=#{key}")
    String balanceClaim(@Param("owner") long owner, @Param("key") String key);

    @Insert("""
            INSERT INTO nx_trial_balance_submission(user_id,claim_no,command_key,snapshot_json)
            VALUES (#{owner},#{claim},#{key},#{snapshot})
            """)
    int acceptBalance(@Param("owner") long owner, @Param("claim") String claim,
                      @Param("key") String key, @Param("snapshot") String snapshot);

    record Options(long version, String productNos) {}
    record Command(long actorId, String requestJson, String receiptJson) {}
    record Product(long id, String productNo, String name, String productType, String inventoryMode,
                   BigDecimal priceUsdt, Integer stock, String status, Boolean storeVisible, String unlockPhase,
                   String gpuModel, Integer vramTotalGb, String power, String publishBlock) {}
}
