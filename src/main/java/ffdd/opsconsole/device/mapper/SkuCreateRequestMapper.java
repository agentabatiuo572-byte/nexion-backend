package ffdd.opsconsole.device.mapper;

import ffdd.opsconsole.device.application.SkuCreateRequests.Draft;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkuCreateRequestMapper {
    @Insert("""
            INSERT INTO nx_e1_sku_create_request (request_id,maker_id,business_hash)
            VALUES (#{requestId},#{makerId},#{businessHash})
            ON DUPLICATE KEY UPDATE request_id=request_id
            """)
    void ensureDraft(@Param("requestId") String requestId, @Param("makerId") long makerId,
                     @Param("businessHash") String businessHash);

    @Select("""
            SELECT request_id requestId,maker_id makerId,business_hash businessHash,operation_id operationId,
                   result_sku_id resultSkuId,result_json resultJson
              FROM nx_e1_sku_create_request WHERE request_id=#{requestId} FOR UPDATE
            """)
    Draft lockDraft(String requestId);

    @Update("""
            UPDATE nx_e1_sku_create_request SET operation_id=#{operationId}
             WHERE request_id=#{requestId} AND operation_id IS NULL AND result_sku_id IS NULL
            """)
    int bindProposal(@Param("requestId") String requestId, @Param("operationId") String operationId);

    @Update("""
            UPDATE nx_e1_sku_create_request SET result_sku_id=#{skuId},result_json=#{resultJson}
             WHERE request_id=#{requestId} AND result_sku_id IS NULL
            """)
    int finish(@Param("requestId") String requestId, @Param("skuId") String skuId,
               @Param("resultJson") String resultJson);

    @Insert("""
            INSERT INTO nx_e1_sku_number_sequence (prefix,last_number) VALUES (#{prefix},0)
            ON DUPLICATE KEY UPDATE prefix=prefix
            """)
    void ensureSequence(String prefix);

    @Select("SELECT last_number FROM nx_e1_sku_number_sequence WHERE prefix=#{prefix} FOR UPDATE")
    int lockSequence(String prefix);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM nx_product WHERE product_no=#{skuId})
                OR EXISTS(SELECT 1 FROM nx_admin_device_sku WHERE sku_id=#{skuId})
            """)
    boolean skuNumberOccupied(String skuId);

    @Update("UPDATE nx_e1_sku_number_sequence SET last_number=#{number} WHERE prefix=#{prefix}")
    void advanceSequence(@Param("prefix") String prefix, @Param("number") int number);
}
