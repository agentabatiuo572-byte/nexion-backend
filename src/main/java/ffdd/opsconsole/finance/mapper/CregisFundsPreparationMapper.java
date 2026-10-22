package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CregisFundsPreparationMapper extends BaseMapper<Object> {
    String FIELDS = "CAST(id AS CHAR) id,plan_no planNo,kind,source_address sourceAddress,target_address targetAddress,"
            + "CAST(amount_usdt AS CHAR) amountUsdt,business_reference businessReference,reason,state,"
            + "CAST(maker_id AS CHAR) makerId,CAST(checker_id AS CHAR) checkerId,decision_reason decisionReason,"
            + "version,created_at createdAt,updated_at updatedAt";

    @Select("SELECT platform_address platformAddress,counterparty_address counterpartyAddress,network,asset "
            + "FROM nx_cregis_funds_preparation_config WHERE project_id=#{project}")
    Map<String,Object> config(long project);

    @Select("SELECT address,CAST(user_id AS CHAR) userId FROM nx_cregis_deposit_address "
            + "WHERE project_id=#{project} AND chain_id='2510' AND state='READY' AND user_id IS NOT NULL ORDER BY id LIMIT 60")
    List<Map<String,Object>> sources(long project);

    @Select("SELECT COUNT(*) FROM nx_cregis_deposit_address WHERE project_id=#{project} AND chain_id='2510' "
            + "AND address=#{address} AND state='READY' AND user_id IS NOT NULL")
    int sourceExists(@Param("project") long project,@Param("address") String address);

    @Select("SELECT " + FIELDS + " FROM nx_cregis_funds_preparation_plan WHERE project_id=#{project} "
            + "AND (#{before}=0 OR id<#{before}) ORDER BY id DESC LIMIT #{limit}")
    List<Map<String,Object>> plans(@Param("project") long project,@Param("before") long before,@Param("limit") int limit);

    @Select("SELECT " + FIELDS + " FROM nx_cregis_funds_preparation_plan WHERE project_id=#{project} AND plan_no=#{no} FOR UPDATE")
    Map<String,Object> lock(@Param("project") long project,@Param("no") String no);

    @Select("SELECT " + FIELDS + " FROM nx_cregis_funds_preparation_plan WHERE project_id=#{project} AND plan_no=#{no}")
    Map<String,Object> plan(@Param("project") long project,@Param("no") String no);

    @Insert("INSERT INTO nx_cregis_funds_preparation_plan(plan_no,project_id,kind,source_address,target_address,amount_usdt,"
            + "business_reference,reason,maker_id) VALUES(#{no},#{project},#{kind},#{source},#{target},#{amount},#{reference},#{reason},#{actor})")
    int insert(@Param("no") String no,@Param("project") long project,@Param("kind") String kind,
               @Param("source") String source,@Param("target") String target,@Param("amount") BigDecimal amount,
               @Param("reference") String reference,@Param("reason") String reason,@Param("actor") long actor);

    @Update("UPDATE nx_cregis_funds_preparation_plan SET state=#{state},checker_id=#{actor},decision_reason=#{reason},version=version+1 "
            + "WHERE project_id=#{project} AND plan_no=#{no} AND version=#{version} AND state=#{previous}")
    int decide(@Param("project") long project,@Param("no") String no,@Param("version") long version,
               @Param("previous") String previous,@Param("state") String state,@Param("actor") long actor,@Param("reason") String reason);
}
