package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import java.nio.file.*;
import java.util.*;
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
        String eligibility=SupportBindingMapper.ELIGIBLE_AGENT_FROM.replaceAll("\\s+"," ").trim();
        for(String method:List.of("handoverCount","handover")) {
            var statement=configuration.getMappedStatement(SupportBindingMapper.class.getName()+"."+method);
            for(Long agent:Arrays.<Long>asList(null,7L)) {
                var args=new HashMap<String,Object>();args.put("agent",agent);args.put("unavailable",true);
                args.put("offset",0L);args.put("limit",20);
                var filtered=statement.getBoundSql(args);
                assertThat(filtered.getSql().replaceAll("\\s+"," ").trim()).as(method+" unavailable filter")
                    .contains("AND NOT EXISTS(SELECT 1 "+eligibility+" AND a.id=x.agent_admin_id)");
                assertThat(filtered.getParameterMappings()).extracting(p->p.getProperty())
                    .containsExactlyElementsOf(agent==null
                        ? (method.equals("handover")?List.of("limit","offset"):List.of())
                        : (method.equals("handover")?List.of("agent","limit","offset"):List.of("agent")));
                args.put("unavailable",false);
                assertThat(statement.getBoundSql(args).getSql()).doesNotContain("nx_support_account_qualification_history");
            }
        }
    }
}
