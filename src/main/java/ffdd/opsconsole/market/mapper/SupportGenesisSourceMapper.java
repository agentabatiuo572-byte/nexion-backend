package ffdd.opsconsole.market.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Cross-table original-source projection has no entity CRUD surface.
public interface SupportGenesisSourceMapper {
    String ORDER="""
        SELECT o.id,o.order_no orderNo,o.client_request_no clientRequestNo,o.user_id customerId,
          o.series_code seriesCode,o.quantity,o.unit_price_usdt unitPrice,o.amount_usdt amount,
          o.payment_asset currency,o.status,o.order_type orderType,o.seller_user_id sellerId,
          o.holding_no holdingNo,o.royalty_usdt royalty,o.wallet_ledger_id walletLedgerId,
          o.paid_at paidAt,o.completed_at completedAt,o.is_deleted deleted,
          b.sandbox buyerSandbox,b.is_deleted buyerDeleted,s.sandbox sellerSandbox,s.is_deleted sellerDeleted
        FROM nx_genesis_order o LEFT JOIN nx_user b ON b.id=o.user_id
          LEFT JOIN nx_user s ON s.id=o.seller_user_id
        """;
    @Select("<script>"+ORDER+" WHERE o.user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> OR o.seller_user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY o.id</script>")
    List<Map<String,Object>> ordersByCustomers(@Param("ids") List<Long> ids);
    @Select("<script>"+ORDER+" WHERE o.order_no IN <foreach collection='nos' item='no' open='(' separator=',' close=')'>#{no}</foreach> ORDER BY o.id</script>")
    List<Map<String,Object>> ordersByNos(@Param("nos") List<String> nos);
    @Select("""
        <script>SELECT id,user_id customerId,biz_no businessId,biz_type ledgerType,asset currency,direction,
          amount,balance_after balanceAfter,status,created_at successAt,updated_at updatedAt,is_deleted deleted,remark
        FROM nx_wallet_ledger WHERE biz_no IN
          <foreach collection='refs' item='ref' open='(' separator=',' close=')'>#{ref}</foreach>
        ORDER BY id</script>
        """)
    List<Map<String,Object>> ledgers(@Param("refs") List<String> refs);
    @Select("""
        SELECT id,holding_no holdingNo,user_id customerId,order_no orderNo,series_code seriesCode,
          acquired_price_usdt acquiredPrice,status,listing_price_usdt listingPrice,
          acquired_at acquiredAt,listed_at listedAt,is_deleted deleted
        FROM nx_genesis_holding WHERE holding_no=#{no}
        """)
    Map<String,Object> holding(@Param("no") String no);
}
