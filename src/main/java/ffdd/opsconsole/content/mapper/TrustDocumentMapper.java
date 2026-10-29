package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.TrustDocumentRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface TrustDocumentMapper {
    String FIELDS = "slot,version_label AS version,language,reference_no AS referenceNo,verify_url AS verifyUrl,"
            + "object_key AS objectKey,file_name AS fileName,size_bytes AS sizeBytes,page_count AS pageCount,sha256,updated_by AS updatedBy,updated_at AS updatedAt";

    @Select("SELECT " + FIELDS + " FROM nx_trust_document")
    List<TrustDocumentRow> list();

    @Select("SELECT " + FIELDS + " FROM nx_trust_document WHERE slot=#{slot}")
    TrustDocumentRow find(String slot);

    @Select("SELECT " + FIELDS + " FROM nx_trust_document WHERE slot=#{slot} FOR UPDATE")
    TrustDocumentRow lock(String slot);

    @Insert("""
            INSERT INTO nx_trust_document(slot,version_label,language,reference_no,verify_url,object_key,file_name,size_bytes,page_count,sha256,updated_by)
            VALUES(#{slot},#{version},#{language},#{referenceNo},#{verifyUrl},#{objectKey},#{fileName},#{sizeBytes},#{pageCount},#{sha256},#{updatedBy})
            ON DUPLICATE KEY UPDATE version_label=VALUES(version_label),language=VALUES(language),reference_no=VALUES(reference_no),
              verify_url=VALUES(verify_url),object_key=VALUES(object_key),file_name=VALUES(file_name),size_bytes=VALUES(size_bytes),page_count=VALUES(page_count),
              sha256=VALUES(sha256),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(6)
            """)
    int upsert(TrustDocumentRow row);

    @Delete("DELETE FROM nx_trust_document WHERE slot=#{slot}")
    int delete(String slot);
}
