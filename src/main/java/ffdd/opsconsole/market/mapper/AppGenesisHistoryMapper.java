package ffdd.opsconsole.market.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only, bounded keyset pages. Internal IDs are removed by the service. */
@Mapper
public interface AppGenesisHistoryMapper {
    @Select("""
        SELECT h.id AS cursorId,h.holding_no AS holdingNo,h.series_code AS seriesCode,
               h.listing_price_usdt AS askPriceUsdt,h.listed_at AS listedAt,
               CONCAT('usr_',RIGHT(UPPER(HEX(h.user_id)),4)) AS seller
          FROM nx_genesis_holding h JOIN nx_user u ON u.id=h.user_id
         WHERE h.is_deleted=0 AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
           AND UPPER(h.status)='LISTED' AND h.listing_price_usdt>0 AND h.id < #{beforeId}
         ORDER BY h.id DESC LIMIT 101
        """)
    List<Map<String, Object>> listings(@Param("beforeId") long beforeId);

    @Select("""
        SELECT o.id AS cursorId,CONCAT('tx_',LEFT(SHA2(o.order_no,256),16)) AS orderNo,
               UPPER(o.order_type) AS orderType,o.quantity,o.unit_price_usdt AS unitPriceUsdt,
               o.amount_usdt AS amountUsdt,o.royalty_usdt AS royaltyUsdt,o.completed_at AS completedAt
          FROM nx_genesis_order o JOIN nx_user u ON u.id=o.user_id
         WHERE o.is_deleted=0 AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
           AND UPPER(o.status)='COMPLETED' AND UPPER(o.order_type) IN ('PRIMARY','SECONDARY')
           AND o.id < #{beforeId} ORDER BY o.id DESC LIMIT 101
        """)
    List<Map<String, Object>> transactions(@Param("beforeId") long beforeId);

    @Select("""
        SELECT o.id AS cursorId,o.order_no AS orderNo,UPPER(o.order_type) AS orderType,
               o.quantity,o.unit_price_usdt AS unitPriceUsdt,o.amount_usdt AS amountUsdt,
               o.royalty_usdt AS royaltyUsdt,o.completed_at AS completedAt
          FROM nx_genesis_order o JOIN nx_user u ON u.id=o.user_id
         WHERE o.user_id=#{userId} AND o.is_deleted=0 AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
           AND UPPER(o.status)='COMPLETED' AND UPPER(o.order_type) IN ('PRIMARY','SECONDARY')
           AND o.id < #{beforeId} ORDER BY o.id DESC LIMIT 101
        """)
    List<Map<String, Object>> orders(@Param("userId") long userId, @Param("beforeId") long beforeId);

    @Select("""
        SELECT i.id AS cursorId,i.batch_no AS batchNo,i.holding_no AS holdingNo,
               i.amount_usdt AS amountUsdt,UPPER(i.status) AS status,i.paid_at AS paidAt
          FROM nx_genesis_emission_item i JOIN nx_user u ON u.id=i.user_id
         WHERE i.user_id=#{userId} AND i.is_deleted=0 AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
           AND i.id < #{beforeId} ORDER BY i.id DESC LIMIT 101
        """)
    List<Map<String, Object>> emissions(@Param("userId") long userId, @Param("beforeId") long beforeId);
}
