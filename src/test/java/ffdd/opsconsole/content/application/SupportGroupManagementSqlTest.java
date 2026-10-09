package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportGroupManagementSqlTest {
    final Configuration configuration=new Configuration();
    SupportGroupManagementSqlTest(){configuration.addMapper(SupportGroupMapper.class);configuration.addMapper(SupportBindingMapper.class);}
    private String sql(Class<?> type,String method,Map<String,Object> parameters){return configuration.getMappedStatement(type.getName()+"."+method).getBoundSql(parameters).getSql().replaceAll("\\s+"," ");}
    @Test void accountManagementQueryUsesCurrentServerScopeAndOnlyNickname(){
        var managed=Map.<String,Object>of("scope",new ReadScope(7L,ReadMode.MANAGED,null,13L),"id",13L);
        assertThat(sql(SupportGroupMapper.class,"managementAccount",managed)).contains("scope_group.supervisor_admin_id=", "scope_q.qualification_kind='SUPERVISOR'", "scope_agent.id=", "scope_agent.nickname").doesNotContain("username","email","${");
        assertThat(sql(SupportGroupMapper.class,"qualificationAccount",Map.of("scope",new ReadScope(7L,ReadMode.ALL,null,null),"id",13L)))
                .contains("scope_role.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN')","a.version","a.nickname").doesNotContain("nx_admin_account_state","username","email");
    }
    @Test void qualificationReaderPreservesRemovedStateAndRejectsTemporalConflict(){
        var text=sql(SupportGroupMapper.class,"qualificationForRead",Map.of("id",13L,"kind","SERVICE"));
        assertThat(text).contains("scope_q.state","scope_q.version","scope_q.starts_at <= UTC_TIMESTAMP(6)","scope_q.ends_at IS NULL","NOT EXISTS","scope_q_other.ends_at>UTC_TIMESTAMP(6)","FOR SHARE")
                .doesNotContain("state='ENABLED'","state<>'REMOVED'","LIMIT 1");
    }
    @Test void qualificationWriteReadsTheSameUniqueCurrentFactAndCountsAllHistoryBeforeFirstWrite(){
        var args=Map.<String,Object>of("id",13L,"kind","SERVICE");
        assertThat(sql(SupportGroupMapper.class,"qualification",args)).isEqualTo(sql(SupportGroupMapper.class,"qualificationForRead",args));
        for(String method:List.of("memberHistoryCount","qualificationHistoryCount","routeHistoryCount")) {
            assertThat(sql(SupportGroupMapper.class,method,args)).contains("SELECT COUNT(*)","FOR SHARE").doesNotContain("ends_at IS NULL","LIMIT 1");
        }
    }
    @Test void routeProjectionKeepsPoolVersionAndUsesIndependentUniqueSafeCurrentFact(){
        for(ReadScope scope:List.of(new ReadScope(7L,ReadMode.ALL,null,null),new ReadScope(7L,ReadMode.MANAGED,8L,null))) {
            var args=new HashMap<String,Object>();args.put("scope",scope);args.put("offset",0);args.put("limit",20);
            var text=sql(SupportBindingMapper.class,"scopedPool",args);
            assertThat(text).contains("p.version","routeState","routeId","routeGroupId","routeVersion","'ABSENT'","'UNKNOWN'","'AVAILABLE'",
                "read_route.version BETWEEN 1 AND 9007199254740991","other_route.id<>read_route.id","read_route.starts_at <= UTC_TIMESTAMP(6)","history_route.customer_id=p.customer_id")
                .doesNotContain("COALESCE(read_route.version,0)","${","&lt;");
            if(scope.mode()==ReadMode.MANAGED)assertThat(text).contains("scope_group.supervisor_admin_id=","scope_q.qualification_kind='SUPERVISOR'");
            assertThat(text.substring(text.indexOf("FROM nx_support_binding_pool p"),text.indexOf(" ORDER BY p.customer_id")))
                .isEqualTo(sql(SupportBindingMapper.class,"scopedPoolCount",args).substring("SELECT COUNT(*) ".length(),sql(SupportBindingMapper.class,"scopedPoolCount",args).lastIndexOf(" FOR SHARE")));
        }
    }
}
