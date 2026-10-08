package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.util.*;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Builds the actual provider SQL, including XML/OGNL and bound parameter names; no database substitute. */
class SupportContentScopeSqlTest {
    @Test void historicalSenderGrantUsesCurrentReadRatherThanAnOldRepeatableReadSnapshot() throws Exception {
        String source = String.join("\n", SupportBulkMapper.class
                .getMethod("readerGrant", Long.class, boolean.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(source).contains("a.status=1", "r.status=1", "p.status=1", "service_m3_read", "FOR SHARE");
    }

    @Test void conversationCountPageAndCursorKeepTheSameScopeBeforeNarrowing() {
        for(ReadMode mode:ReadMode.values()) {
            var query=query(mode);query.put("beforeId",90L);query.put("stableCursor",true);
            String count=sql(ConversationMapper.ScopedSql.count(),query);
            String page=sql(ConversationMapper.ScopedSql.page(),query);
            assertThat(count).contains("scope_customer.id=c.user_id");
            assertThat(page).contains("scope_customer.id=c.user_id","c.id < ?");
            assertThat(where(count)).isEqualTo(where(page).split("AND c.id <",2)[0].trim());
        }
    }
    @Test void ticketTabDoesNotShadowAuthorizationScopeAndCountMatchesPage() {
        for(ReadMode mode:ReadMode.values()) for(String tab:List.of("active","resolved","archived","all")) {
            var query=query(mode);query.put("ticketScope",tab);
            String count=sql(SupportTicketMapper.ScopedSql.count(),query);
            String page=sql(SupportTicketMapper.ScopedSql.page(),query);
            assertThat(count).contains("scope_customer.id=t.user_id");
            assertThat(where(count)).isEqualTo(where(page));
            if(tab.equals("active")) assertThat(count).contains("t.status IN ('OPEN','IN_PROGRESS','PENDING_USER')");
            if(tab.equals("archived")) assertThat(count).contains("t.archived=1");
        }
    }
    @Test void nullScopeDeniesConversationTicketAndEveryBulkArm() {
        var query=query(null);
        for(String source:List.of(ConversationMapper.ScopedSql.count(),SupportTicketMapper.ScopedSql.count(),
                SupportBulkMapper.ScopedSql.recipientCount(),SupportBulkMapper.ScopedSql.jobCount())) {
            assertThat(sql(source,query)).contains("1=0");
        }
    }
    @Test void bulkManagementListCannotUseSenderOrPersonalFallbackAndDetailsBindEachScope() {
        var query=query(ReadMode.MANAGED);
        query.put("senderSummary",true);
        String list=sql(SupportBulkMapper.ScopedSql.jobs(),query);
        assertThat(list).doesNotContain("OR j.actor_id=");
        assertThat(where(list)).isEqualTo(where(sql(SupportBulkMapper.ScopedSql.jobCount(),query)));
        query.put("managedScope",new ReadScope(7L,ReadMode.MANAGED,null,null));
        query.put("personalScope",new ReadScope(7L,ReadMode.PERSONAL,null,null));
        query.put("scope",new ReadScope(7L,ReadMode.ALL,null,null));
        var bound=new XMLLanguageDriver().createSqlSource(new Configuration(),SupportBulkMapper.ScopedSql.recipients(),Map.class).getBoundSql(query);
        assertThat(bound.getParameterMappings().stream().map(p->p.getProperty()).toList())
                .contains("scope.actorId","managedScope.actorId","personalScope.actorId");
        assertThat(where(bound.getSql().replaceAll("\\s+"," ").trim()))
                .isEqualTo(where(sql(SupportBulkMapper.ScopedSql.recipientCount(),query)))
                .isEqualTo(where(sql(SupportBulkMapper.ScopedSql.counts(),query)));
    }
    @Test void senderSummaryFallbackOnlyAppliesToItsOwnPersonalJobHeaders() {
        var query=query(ReadMode.PERSONAL);
        assertThat(sql(SupportBulkMapper.ScopedSql.jobs(),query)).doesNotContain("OR j.actor_id=");
        query.put("senderSummary",true);
        String headers=sql(SupportBulkMapper.ScopedSql.jobs(),query);
        assertThat(headers).contains("OR j.actor_id=?", "FOR SHARE");
        assertThat(where(headers)).isEqualTo(where(sql(SupportBulkMapper.ScopedSql.jobCount(),query)));
        assertThat(sql(SupportBulkMapper.ScopedSql.recipients(),query))
                .contains("scope_binding.agent_admin_id=?", "FOR SHARE").doesNotContain("OR j.actor_id=");
        query.put("scope",null);
        assertThat(sql(SupportBulkMapper.ScopedSql.jobs(),query)).contains("1=0").doesNotContain("OR j.actor_id=");
    }
    private static Map<String,Object> query(ReadMode mode) {
        var values=new HashMap<String,Object>();
        values.put("scope",mode==null?null:new ReadScope(7L,mode,null,null));
        values.put("managedScope",null);values.put("personalScope",null);
        values.put("senderSummary",false);
        values.put("ticketScope","all");values.put("keyword","needle");
        values.put("visibility",new SupportTicketMapper.Visibility(7L,null,true,true));
        values.put("batch","batch-test");values.put("pageSize",20L);values.put("limit",20);values.put("offset",0L);
        values.put("stableCursor",false);return values;
    }
    private static String sql(String source,Map<String,Object> query) {
        return new XMLLanguageDriver().createSqlSource(new Configuration(),source,Map.class)
                .getBoundSql(query).getSql().replaceAll("\\s+"," ").trim();
    }
    private static String where(String sql) {
        // Last projection subqueries precede the outer FROM; seek the known one-to-one scope join where applicable.
        int join=sql.indexOf("JOIN nx_user scope_customer ON scope_customer.id=");
        int start=sql.indexOf(" WHERE ",join<0?0:join);
        String value=sql.substring(start+7);
        return value.split(" ORDER BY (?:c\\.|t\\.|j\\.|r\\.|COALESCE)",2)[0].trim();
    }
}
