package ffdd.opsconsole.content.terms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.terms.infrastructure.LegalTermsAckEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LegalTermsAckMapper extends BaseMapper<LegalTermsAckEntity> {
    @Select("SELECT * FROM nx_legal_terms_ack WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND locale=#{locale} AND jurisdiction=#{jurisdiction} AND is_deleted=0 LIMIT 1")
    LegalTermsAckEntity findOne(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId, @Param("locale") String locale, @Param("jurisdiction") String jurisdiction);
}
