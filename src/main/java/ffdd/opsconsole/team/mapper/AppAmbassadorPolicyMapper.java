package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only boundary for the server-owned ambassador application policy. */
@Mapper
public interface AppAmbassadorPolicyMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox,v_rank vRank FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope user(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    @Select("""
            SELECT policy_version policyVersion,revision,default_budget_usdt defaultBudgetUsdt,buckets_json bucketsJson
              FROM nx_team_ambassador_policy
             WHERE policy_key='default' AND active=1 AND is_deleted=0
             LIMIT 1
            """)
    PolicyRow policy();

    record UserScope(Integer sandbox, String vRank) { }
    record PolicyRow(String policyVersion, Long revision, BigDecimal defaultBudgetUsdt, String bucketsJson) { }
}
