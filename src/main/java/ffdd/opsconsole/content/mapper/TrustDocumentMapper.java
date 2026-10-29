package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.TrustDocumentRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface TrustDocumentMapper {
    String FIELDS = "slot,version_label AS version,language,reference_no AS referenceNo,verify_url AS verifyUrl,"
            + "object_key AS objectKey,file_name AS fileName,size_bytes AS sizeBytes,page_count AS pageCount,sha256,updated_by AS updatedBy,updated_at AS updatedAt";

    @Select("SELECT " + FIELDS + " FROM nx_trust_document")
    List<TrustDocumentRow> list();

    @Select("SELECT " + FIELDS + " FROM nx_trust_document WHERE slot=#{slot}")
    TrustDocumentRow find(String slot);

    /** First upload to a slot; a concurrent first upload makes this fail on the primary key. */
    @Insert("""
            INSERT INTO nx_trust_document(slot,version_label,language,reference_no,verify_url,object_key,file_name,size_bytes,page_count,sha256,updated_by)
            VALUES(#{slot},#{version},#{language},#{referenceNo},#{verifyUrl},#{objectKey},#{fileName},#{sizeBytes},#{pageCount},#{sha256},#{updatedBy})
            """)
    int insert(TrustDocumentRow row);

    /** Replaces the slot's file only while it still holds the file the operator saw. */
    @Update("""
            UPDATE nx_trust_document SET version_label=#{row.version},language=#{row.language},reference_no=#{row.referenceNo},
              verify_url=#{row.verifyUrl},object_key=#{row.objectKey},file_name=#{row.fileName},size_bytes=#{row.sizeBytes},
              page_count=#{row.pageCount},sha256=#{row.sha256},updated_by=#{row.updatedBy},updated_at=CURRENT_TIMESTAMP(6)
            WHERE slot=#{row.slot} AND sha256=#{expectedSha256}
            """)
    int replace(@Param("row") TrustDocumentRow row, @Param("expectedSha256") String expectedSha256);

    @Delete("DELETE FROM nx_trust_document WHERE slot=#{slot} AND sha256=#{expectedSha256}")
    int deleteIfCurrent(@Param("slot") String slot, @Param("expectedSha256") String expectedSha256);
}
