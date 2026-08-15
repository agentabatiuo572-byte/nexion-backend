package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only, environment-scoped projection for unauthenticated referral links. */
@Mapper
public interface PublicSponsorPreviewMapper extends BaseMapper<Object> {
    @Select("""
            SELECT referral_code AS referralCode, nickname, v_rank AS vRank,
                   COALESCE(sandbox, 0) AS sandbox
              FROM nx_user
             WHERE UPPER(REPLACE(referral_code, '-', '')) = #{canonicalCode}
               AND status = 'ACTIVE' AND is_deleted = 0
             LIMIT 2
            """)
    List<SponsorRow> findActiveByCanonicalCode(@Param("canonicalCode") String canonicalCode);

    record SponsorRow(String referralCode, String nickname, String vRank, Integer sandbox) {
    }
}
