package ffdd.opsconsole.shared.canonical.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** A SKU's gate and current-generation pending external purchases in one database read. */
public interface PurchaseGateQuotaMapper {
    // The outer query aliases the SKU as s, including in the settlement UPDATE statements.
    String HELD = """
            COALESCE((SELECT SUM(h.quantity) FROM nx_purchase_e1_hold h
                       WHERE h.sku_id=s.sku_id AND h.gate_generation=s.purchase_gate_generation
                         AND h.status='HELD'),0)
            """;
    String GATE_COLUMNS = "s.sku_id skuId,s.purchase_gate_json gateJson,s.purchase_gate_generation generation,"
            + HELD + " held";
    String GATE_BY_SKU = "SELECT " + GATE_COLUMNS + " FROM nx_admin_device_sku s"
            + " WHERE s.sku_id=#{productNo} AND s.is_deleted=0";

    @Select(GATE_BY_SKU)
    GateQuota findGateQuota(@Param("productNo") String productNo);

    @Select(GATE_BY_SKU + " FOR UPDATE")
    GateQuota lockGateQuota(@Param("productNo") String productNo);

    @Select("<script>SELECT " + GATE_COLUMNS + """
              FROM nx_admin_device_sku s WHERE s.is_deleted=0 AND s.sku_id IN
              <foreach collection='productNos' item='no' open='(' separator=',' close=')'>#{no}</foreach>
            </script>
            """)
    List<GateQuota> findGateQuotas(@Param("productNos") List<String> productNos);

    record GateQuota(String skuId, String gateJson, long generation, long held) { }
}
