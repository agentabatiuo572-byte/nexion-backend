package ffdd.opsconsole.content.terms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.terms.infrastructure.LegalTermsVersionEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface LegalTermsVersionMapper extends BaseMapper<LegalTermsVersionEntity> {
    @Update("UPDATE nx_legal_terms_version SET status='SUPERSEDED', updated_at=#{now} WHERE locale=#{locale} AND jurisdiction=#{jurisdiction} AND status='PUBLISHED' AND is_deleted=0")
    int supersede(@Param("locale") String locale, @Param("jurisdiction") String jurisdiction, @Param("now") LocalDateTime now);
    @Update("UPDATE nx_legal_terms_version SET status='PUBLISHED', revision=revision+1, published_at=#{row.publishedAt}, last_operator=#{row.lastOperator}, updated_at=#{row.updatedAt} WHERE id=#{row.id} AND revision=#{expectedRevision} AND status='DRAFT' AND is_deleted=0")
    int updatePublished(@Param("row") LegalTermsVersionEntity row, @Param("expectedRevision") long expectedRevision);
    @Update("UPDATE nx_legal_terms_version SET status='REVOKED', revision=revision+1, revoked_at=#{row.revokedAt}, last_operator=#{row.lastOperator}, updated_at=#{row.updatedAt} WHERE id=#{row.id} AND revision=#{expectedRevision} AND status='PUBLISHED' AND is_deleted=0")
    int updateRevoked(@Param("row") LegalTermsVersionEntity row, @Param("expectedRevision") long expectedRevision);
}
