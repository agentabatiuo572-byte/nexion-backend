package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VietQrReceiptEvidenceMapper extends BaseMapper<Object> {
    @Insert("""
            INSERT INTO nx_vietqr_receipt_evidence (
                asset_id, object_key, purpose, content_type, size_bytes,
                content_sha256, uploaded_by, status,
                created_at, updated_at, is_deleted, version
            ) VALUES (
                #{assetId}, #{objectKey}, #{purpose}, #{contentType}, #{sizeBytes},
                #{contentSha256}, #{uploadedBy}, 'AVAILABLE',
                NOW(), NOW(), 0, 0
            )
            """)
    int insertAvailableEvidence(
            @Param("assetId") String assetId,
            @Param("objectKey") String objectKey,
            @Param("purpose") String purpose,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("contentSha256") String contentSha256,
            @Param("uploadedBy") String uploadedBy);

    @Update("""
            UPDATE nx_vietqr_receipt_evidence
               SET status = 'BOUND',
                   bound_resource_type = #{resourceType},
                   bound_resource_id = #{resourceId},
                   bound_by = #{boundBy},
                   bound_at = NOW(),
                   version = version + 1,
                   updated_at = NOW()
             WHERE asset_id = #{assetId}
               AND purpose = #{purpose}
               AND status = 'AVAILABLE'
               AND bound_resource_id IS NULL
               AND is_deleted = 0
            """)
    int bindAvailableEvidence(
            @Param("assetId") String assetId,
            @Param("purpose") String purpose,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("boundBy") String boundBy);
}
