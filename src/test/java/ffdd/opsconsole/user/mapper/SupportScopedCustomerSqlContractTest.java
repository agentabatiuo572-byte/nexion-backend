package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportScopedCustomerSqlContractTest {
    @Test void overviewAndExportReplayUseTheSameCurrentCustomerBoundary() throws Exception {
        for (String method : List.of("supportOverview", "countReadableSupportCustomers")) {
            var reflected = Arrays.stream(UserOpsMapper.class.getMethods())
                    .filter(m -> m.getName().equals(method)).findFirst().orElseThrow();
            var sqlSource = new XMLLanguageDriver().createSqlSource(new Configuration(),
                    String.join("\n", reflected.getAnnotation(Select.class).value()), Map.class);
            var query = new HashMap<String, Object>();
            query.put("scope", new ReadScope(7L, ReadMode.MANAGED, 10L, null));
            query.put("customerIds", List.of(23L, 24L));
            assertThat(sqlSource.getBoundSql(query).getSql())
                    .contains("nx_support_group_owner_history", "scope_customer.is_deleted=0", "FOR SHARE");
            query.put("scope", null);
            assertThat(sqlSource.getBoundSql(query).getSql()).contains("1=0");
            if (method.equals("countReadableSupportCustomers")) {
                query.put("scope", new ReadScope(7L, ReadMode.MANAGED, 10L, null));
                query.put("customerIds", List.of());
                assertThat(sqlSource.getBoundSql(query).getSql()).contains("1=0");
            }
        }
    }

    @Test void countPageAndDirectProfileRecheckScopeAndDenyNullBeforePagination() throws Exception {
        for(String method:List.of("countScopedUsersByQuery","pageScopedUsers","findScopedById")) {
            var reflected=Arrays.stream(UserOpsMapper.class.getMethods()).filter(m->m.getName().equals(method)).findFirst().orElseThrow();
            String source=String.join("\n",reflected.getAnnotation(Select.class).value());
            var driver=new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class);
            var q=new HashMap<String,Object>();q.put("query",UserQueryRequest.basic("Alice",null,null,1,20,null));
            q.put("statuses",List.of());q.put("phoneKeyword",null);q.put("offset",0L);q.put("pageSize",20);q.put("userId",23L);
            for(ReadMode mode:ReadMode.values()) {
                q.put("scope",new ReadScope(7L,mode,mode==ReadMode.PERSONAL?null:10L,null));
                var bound=driver.getBoundSql(q);String sql=bound.getSql();
                assertThat(sql).contains("JOIN nx_user scope_customer ON scope_customer.id=u.id","FOR SHARE").doesNotContain("${");
                assertThat(bound.getParameterMappings()).extracting("property").contains("scope.actorId");
                if(mode==ReadMode.MANAGED) assertThat(sql).contains("nx_support_group_owner_history","nx_support_customer_route_history");
                if(mode==ReadMode.PERSONAL) assertThat(sql).contains("scope_binding.agent_admin_id=?").doesNotContain("scope_group.supervisor_admin_id=?");
                if(method.equals("pageScopedUsers")) assertThat(sql.indexOf("scope_customer.is_deleted=0")).isLessThan(sql.lastIndexOf("LIMIT"));
            }
            q.put("scope",null);assertThat(driver.getBoundSql(q).getSql()).contains("1=0");
        }
    }
}
