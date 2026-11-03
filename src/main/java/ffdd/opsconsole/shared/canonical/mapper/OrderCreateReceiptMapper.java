package ffdd.opsconsole.shared.canonical.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Recover a committed creation while its buyer is locked. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface OrderCreateReceiptMapper {
    ObjectReader RECEIPT = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readerFor(new TypeReference<ApiResult<Map<String, Object>>>() { });

    static ApiResult<Map<String, Object>> readReceipt(String json, String requestedQuote) {
        if (json == null) return null;
        try {
            ApiResult<Map<String,Object>> result = RECEIPT.readValue(json);
            result.getData().remove("recoveredQuoteId");
            if (requestedQuote != null && !requestedQuote.equals(result.getData().get("promotionQuoteId")))
                result.getData().put("recoveredQuoteId",requestedQuote);
            return result;
        } catch (JsonProcessingException malformedReceipt) {
            throw new BizException(409, "ORDER_CREATE_RECEIPT_INVALID");
        }
    }

    String PENDING_RECEIPT = """
            SELECT r.response_json
              FROM nx_admin_idempotency_record r
              JOIN nx_order o ON o.order_no=JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.orderNo'))
              JOIN nx_user u ON u.id=o.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
              LEFT JOIN nx_promotion_order_receipt pr ON pr.order_no=o.order_no
             WHERE r.is_deleted=0 AND r.status='SUCCEEDED' AND r.created_at >= NOW() - INTERVAL 24 HOUR
               AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.code'))='0'
               AND JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.idSource'))='server'
               AND o.user_id=#{userId} AND o.is_deleted=0 AND o.order_status='PENDING_PAYMENT'
               AND o.payment_status='PENDING' AND o.activation_status='WAITING_PAYMENT'
               AND o.created_at > NOW() - INTERVAL 24 HOUR
               AND o.created_at > TIMESTAMPADD(MINUTE,-#{ttlMinutes},NOW(6))
               AND (pr.pay_by IS NULL OR pr.pay_by > NOW(6))
               AND (pr.quote_id=#{quoteId} OR (pr.quote_id IS NULL AND #{quoteId} IS NULL))
            """;
    String OLDEST = " ORDER BY o.created_at,o.id,r.id LIMIT 1";

    @Select(PENDING_RECEIPT + """
               AND r.scope=CONCAT('APP:ORDER_CREATE:USER:',#{userId})
               AND o.order_type='SINGLE' AND o.quantity=#{quantity}
               AND (#{quoteId} IS NOT NULL OR EXISTS (
                    SELECT 1 FROM nx_product p WHERE p.id=o.product_id AND p.price_usdt*o.quantity=o.subtotal_usdt))
               AND (#{productId} IS NULL OR o.product_id=#{productId})
               AND (#{productNo} IS NULL OR BINARY COALESCE(
                      (SELECT i.product_no FROM nx_order_item i
                        WHERE i.order_no=o.order_no AND i.product_id=o.product_id AND i.is_deleted=0 LIMIT 1),
                      (SELECT p.product_no FROM nx_product p WHERE p.id=o.product_id)) = BINARY #{productNo})
               AND (CASE WHEN JSON_TYPE(JSON_EXTRACT(r.response_json,'$.data.voucherId'))='STRING'
                         THEN BINARY JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.voucherId')) END)
                   <=> BINARY #{voucherId}
            """ + OLDEST)
    String pendingSingleReceipt(@Param("userId") Long userId, @Param("productId") Long productId,
            @Param("productNo") String productNo, @Param("quantity") int quantity, @Param("voucherId") String voucherId,
            @Param("quoteId") String quoteId, @Param("ttlMinutes") int ttlMinutes);

    @Select("<script>" + PENDING_RECEIPT + """
               AND r.scope=CONCAT('APP:BUNDLE_ORDER_CREATE:USER:',#{userId})
               AND o.order_type='BUNDLE' AND o.amount_usdt=#{amount}
               AND (#{quoteId} IS NOT NULL OR NOT EXISTS (
                    SELECT 1 FROM nx_order_item i LEFT JOIN nx_product p ON p.id=i.product_id
                     WHERE i.order_no=o.order_no AND i.is_deleted=0 AND (p.id IS NULL OR p.price_usdt!=i.unit_price_usdt)))
               AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.response_json,'$.data.policyVersion')) AS UNSIGNED)=#{policyVersion}
               AND (SELECT COUNT(*) FROM nx_order_item i WHERE i.order_no=o.order_no AND i.is_deleted=0)=#{itemCount}
               <foreach collection="quantities" index="productNo" item="qty">
               AND EXISTS (SELECT 1 FROM nx_order_item i WHERE i.order_no=o.order_no AND i.is_deleted=0
                            AND BINARY i.product_no=BINARY #{productNo} AND i.quantity=#{qty})
               </foreach>
            """ + OLDEST + "</script>")
    String pendingBundleReceipt(@Param("userId") Long userId, @Param("quantities") Map<String, Integer> quantities,
            @Param("itemCount") int itemCount,
            @Param("amount") BigDecimal amount, @Param("policyVersion") Long policyVersion,
            @Param("quoteId") String quoteId, @Param("ttlMinutes") int ttlMinutes);
}
