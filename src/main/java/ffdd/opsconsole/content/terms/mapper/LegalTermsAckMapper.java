package ffdd.opsconsole.content.terms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.terms.infrastructure.LegalTermsAckEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LegalTermsAckMapper extends BaseMapper<LegalTermsAckEntity> {
    @Select("SELECT * FROM nx_legal_terms_ack WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND locale=#{locale} AND jurisdiction=#{jurisdiction} AND is_deleted=0 LIMIT 1")
    LegalTermsAckEntity findOne(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId, @Param("locale") String locale, @Param("jurisdiction") String jurisdiction);

    /**
     * Any locale's receipt for this exact published version in the same scope.
     * Prefers the requested locale when several exist so the returned
     * acknowledgedAt stays the one the user actually saw.
     */
    @Select("""
            SELECT * FROM nx_legal_terms_ack
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND jurisdiction=#{jurisdiction} AND version_label=#{version} AND is_deleted=0
             ORDER BY (locale = #{locale}) DESC, acknowledged_at ASC
             LIMIT 1
            """)
    LegalTermsAckEntity findByVersion(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                                      @Param("runId") String runId, @Param("locale") String locale,
                                      @Param("jurisdiction") String jurisdiction, @Param("version") String version);
}
