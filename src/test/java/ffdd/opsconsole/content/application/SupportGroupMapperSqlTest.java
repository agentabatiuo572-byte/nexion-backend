package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportGroupMapperSqlTest {
    @Test void allGroupStatementsCompileAndRemainParameterized(){
        var configuration=new Configuration();configuration.addMapper(SupportGroupMapper.class);
        var args=new HashMap<String,Object>();args.put("owner",7L);
        for(var method:SupportGroupMapper.class.getDeclaredMethods()) {
            var statement=configuration.getMappedStatement(SupportGroupMapper.class.getName()+"."+method.getName());
            assertThat(statement.getBoundSql(args).getSql()).doesNotContain("${");
        }
    }
    @Test void startupIncludesStructureButNeverQualificationAdjudication() throws Exception {
        String installer=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(installer).contains("20261007_support_groups.sql").doesNotContain("20261007_support_groups_qualification_cutover.sql");
        assertThat(installer.indexOf("20261007_support_groups.sql")).isGreaterThan(installer.indexOf("20261001_support_enhancements_bulk.sql"));
        String schema=Files.readString(Path.of("scripts/migrations/20261007_support_groups.sql"));
        assertThat(schema).contains("uk_support_group_member_current","uk_support_group_owner_current","uk_support_qualification_current","uk_support_customer_route_current").doesNotContain("UPDATE nx_support_agent_user_assignment","DROP COLUMN");
    }
    @Test void currentQualificationsReplaceLegacyPositionAndMigrationFallback(){
        assertThat(SupportBindingMapper.SUPERVISOR_PROFILE).contains("scope_q.qualification_kind='SUPERVISOR'")
                .doesNotContain("seat_type","nx_support_migration");
        assertThat(SupportBindingMapper.ELIGIBLE_AGENT_FROM).contains("scope_q.qualification_kind='SERVICE'")
                .doesNotContain("seat_type","nx_support_migration");
    }
    @Test void unavailableHandoverCountAndRowsUseTheCurrentServiceEligibilityContract(){
        var configuration=new Configuration();configuration.addMapper(SupportBindingMapper.class);
        String eligibility=render(configuration,"SELECT 1 "+SupportBindingMapper.ELIGIBLE_AGENT_FROM+" AND a.id=x.agent_admin_id",Map.of());
        assertThat(eligibility).contains("rr.is_deleted=0","r.is_deleted=0 AND r.status=1", "r.role_code IN ('SUPPORT','SUPER_ADMIN')",
                "a.status=1 AND a.is_deleted=0", "p.is_deleted=0 AND p.enabled=1", "scope_q.qualification_kind='SERVICE' AND scope_q.state='ENABLED'")
                .doesNotContain("&lt;","&gt;");
        for(String method:List.of("handoverCount","handover")) {
            var statement=configuration.getMappedStatement(SupportBindingMapper.class.getName()+"."+method);
            for(Long agent:Arrays.<Long>asList(null,7L)) {
                var args=new HashMap<String,Object>();args.put("agent",agent);args.put("unavailable",true);
                args.put("offset",0L);args.put("limit",20);
                var filtered=statement.getBoundSql(args);
                assertThat(filtered.getSql().replaceAll("\\s+"," ").trim()).as(method+" unavailable filter")
                    .contains("AND NOT EXISTS("+eligibility+")");
                assertThat(filtered.getParameterMappings()).extracting(p->p.getProperty())
                    .containsExactlyElementsOf(agent==null
                        ? (method.equals("handover")?List.of("limit","offset"):List.of())
                        : (method.equals("handover")?List.of("agent","limit","offset"):List.of("agent")));
                if(method.equals("handover")) {
                    String count=normalize(configuration.getMappedStatement(SupportBindingMapper.class.getName()+".handoverCount").getBoundSql(args).getSql());
                    String rows=normalize(filtered.getSql());
                    assertThat(rows.substring(rows.indexOf("FROM nx_support_agent_user_assignment x"),rows.indexOf(" ORDER BY x.user_id")))
                            .isEqualTo(count.substring("SELECT COUNT(*) ".length()));
                }
                args.put("unavailable",false);
                assertThat(statement.getBoundSql(args).getSql()).doesNotContain("nx_support_account_qualification_history");
            }
        }
    }

    @Test void scopedUnavailableHandoverKeepsCustomerScopeAndMatchesCountAndRows(){
        var configuration=new Configuration();configuration.addMapper(SupportBindingMapper.class);
        String unavailable="AND NOT EXISTS("+render(configuration,"SELECT 1 "+SupportBindingMapper.ELIGIBLE_AGENT_FROM+" AND a.id=x.agent_admin_id FOR SHARE",Map.of())+")";
        for(ReadScope scope:Arrays.asList(null,new ReadScope(7L,ReadMode.PERSONAL,null,null),
                new ReadScope(7L,ReadMode.MANAGED,null,null),new ReadScope(7L,ReadMode.MANAGED,11L,null),
                new ReadScope(7L,ReadMode.MANAGED,11L,13L),new ReadScope(7L,ReadMode.MANAGED,null,13L),
                new ReadScope(7L,ReadMode.ALL,null,null),new ReadScope(7L,ReadMode.ALL,11L,null),
                new ReadScope(7L,ReadMode.ALL,null,13L),new ReadScope(7L,ReadMode.ALL,11L,13L))) {
            var args=new HashMap<String,Object>();args.put("scope",scope);args.put("offset",0L);args.put("limit",20);
            String customerScope=render(configuration,"SELECT 1 WHERE 1=1 "+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE,args)
                    .substring("SELECT 1 WHERE 1=1 ".length());
            for(boolean filter:List.of(true,false)) {
                args.put("unavailable",filter);
                var count=configuration.getMappedStatement(SupportBindingMapper.class.getName()+".scopedHandoverCount").getBoundSql(args);
                var rows=configuration.getMappedStatement(SupportBindingMapper.class.getName()+".scopedHandover").getBoundSql(args);
                String countSql=normalize(count.getSql()),rowsSql=normalize(rows.getSql());
                assertThat(countSql).as("scoped count "+scope+" unavailable="+filter).contains(customerScope).doesNotContain("${");
                assertThat(rowsSql).contains(customerScope).doesNotContain("${");
                if(scope==null)assertThat(countSql).contains("AND scope_customer.is_deleted=0 AND ( 1=0 )");
                if(filter){assertThat(countSql).contains(unavailable);assertThat(rowsSql).contains(unavailable);}
                else {assertThat(countSql).doesNotContain(unavailable);assertThat(rowsSql).doesNotContain(unavailable);}
                assertThat(rowsSql.substring(rowsSql.indexOf("FROM nx_support_agent_user_assignment x"),rowsSql.indexOf(" ORDER BY x.user_id")))
                        .isEqualTo(countSql.substring("SELECT COUNT(*) ".length(),countSql.lastIndexOf(" FOR SHARE")));
                var parameters=new ArrayList<>(count.getParameterMappings().stream().map(p->p.getProperty()).toList());
                parameters.add("limit");parameters.add("offset");
                assertThat(rows.getParameterMappings()).extracting(p->p.getProperty()).containsExactlyElementsOf(parameters);
            }
        }
    }

    private static String render(Configuration configuration,String sql,Map<String,Object> args){
        return normalize(new XMLLanguageDriver().createSqlSource(configuration,"<script>"+sql+"</script>",Map.class).getBoundSql(args).getSql());
    }
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").trim();}
}
