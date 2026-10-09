package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** SQL generation/source contract only; does not claim database locking or real SQL execution passed. */
class SupportAnalyticsQueryMapperTest {
    static String source(String method) {
        return Arrays.stream(SupportAnalyticsMapper.class.getMethods()).filter(m->m.getName().equals(method)).findFirst().map(m->String.join("\n",m.getAnnotation(Select.class).value())).orElseThrow();
    }
    static String sql(String method,ReadMode mode,List<Long> ids) {
        var p=new HashMap<String,Object>();p.put("scope",new ReadScope(1L,mode,null,null));p.put("ids",ids);p.put("customerId",null);
        p.put("evaluatedAt",java.time.LocalDateTime.of(2026,10,1,8,0));p.put("evaluatedDbAt",java.time.LocalDateTime.of(2026,10,1,8,1));
        p.put("coverageStartAt",null);p.put("dormantCutoff",null);p.put("windowCutoff",null);p.put("maintenanceDays",7);p.put("observedThroughAt",p.get("evaluatedAt"));
        var config=new Configuration();return new XMLLanguageDriver().createSqlSource(config,source(method),Map.class).getBoundSql(p).getSql();
    }
    @Test void currentApiGrantIsLockingExactAndDoesNotUseAuditReplayOrCachedPermissions() {
        assertThat(source("currentReadGrants")).contains("FOR SHARE","a.status=1","a.is_deleted=0","p.resource_type='API'","service_m1_read","service_m3_read").doesNotContain("A2","Redis","cache");
        for(String name:List.of("currentReadGrants","currentGrantedGroupIds")) {
            var options=Arrays.stream(SupportAnalyticsMapper.class.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow().getAnnotation(org.apache.ibatis.annotations.Options.class);
            assertThat(options).as(name+" must physically re-read current authority").isNotNull();
            assertThat(options.useCache()).isFalse();
            assertThat(options.flushCache()).isEqualTo(org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE);
        }
    }
    @Test void allSourceBatchesBindIdsAndNeverApplyOuterPagination() {
        for(String name:List.of("personnelStamps","roleStamps","qualificationStamps","groupStamps","assignmentStamps","routeStamps","memberStamps","ownerStamps","rootDisplayRows","activityIdentityRows","taskRows","pendingReplyRows")) {
            String s=sql(name,ReadMode.MANAGED,List.of(100L,101L));assertThat(s).contains("?").doesNotContain("OFFSET","LIMIT ?");
            assertThat(sql(name,ReadMode.PERSONAL,List.of())).contains("(NULL)");
        }
    }
    @Test void realTaskDueAndReplyIdentityArePresentWithoutActivityWritesOrPlaceholders() {
        assertThat(source("taskRows")).contains("nextDueAt&lt;=#{evaluatedDbAt}","preferencePresent","MIN(m.created_at)","MAX(h.message_id)");
        assertThat(source("pendingReplyRows")).contains("m.id&gt;COALESCE(r.through_message_id,0)","m.created_at","scope_customer.sandbox=0");
        assertThat(source("activityIdentityRows")).contains("e.id eventId","e.seq","e.source_ref","e.occurred_at&lt;=#{observedThroughAt}");
        for(String name:List.of("queryRules","activityIdentityRows","taskRows","pendingReplyRows"))assertThat(source(name).toUpperCase(Locale.ROOT)).doesNotContain("INSERT ","UPDATE ","DELETE ");
    }
    @Test void managedGuardStaysOwnedGroupAndNoGroupsNeverFallsBackToAll() {
        assertThat(sql("currentGrantedGroupIds",ReadMode.MANAGED,List.of())).contains("supervisor_admin_id=?","FOR SHARE");
        assertThat(sql("currentGrantedGroupIds",ReadMode.PERSONAL,List.of())).contains("1=0");
        assertThat(sql("rootDisplayRows",ReadMode.MANAGED,List.of(100L))).contains("scope_customer.sandbox=0","supervisor_admin_id=?");
    }
}
