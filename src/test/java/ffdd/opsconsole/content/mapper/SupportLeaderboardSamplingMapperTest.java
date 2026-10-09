package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportLeaderboardSamplingMapperTest {
    @Test void catalogueUsesOneBoundCutoffAndNoWritesNoCacheOrChangingDatabaseClock(){Configuration configuration=new Configuration();
        new MapperAnnotationBuilder(configuration,SupportLeaderboardSamplingMapper.class).parse();
        for(var method:SupportLeaderboardSamplingMapper.class.getDeclaredMethods()){
            assertFalse(method.isAnnotationPresent(Insert.class));assertFalse(method.isAnnotationPresent(Update.class));assertFalse(method.isAnnotationPresent(Delete.class));
            if(!method.isAnnotationPresent(Select.class))continue;
            String sql=configuration.getMappedStatement(SupportLeaderboardSamplingMapper.class.getName()+"."+method.getName())
                .getBoundSql(Map.of("cutoff",LocalDateTime.parse("2026-10-09T01:00:00"))).getSql();
            assertTrue(sql.contains("?"));assertFalse(sql.contains("${"));assertFalse(sql.contains("UTC_TIMESTAMP"));assertFalse(sql.contains("FOR SHARE"));assertFalse(sql.contains("LIMIT"));
            Options options=method.getAnnotation(Options.class);assertFalse(options.useCache());assertEquals(Options.FlushCachePolicy.TRUE,options.flushCache());}
    }
    @Test void eligibilityOwnerAndMemberPredicatesRemainCurrentAndApiOnly() throws Exception {
        String viewers=String.join("",SupportLeaderboardSamplingMapper.class.getMethod("viewers",LocalDateTime.class).getAnnotation(Select.class).value());
        assertTrue(viewers.contains("a.status=1 AND a.is_deleted=0"));assertTrue(viewers.contains("resource_type='API'"));
        assertTrue(viewers.contains("service_m1_read") && viewers.contains("service_m3_read"));assertTrue(viewers.contains("p.enabled=1 AND p.is_deleted=0"));assertTrue(viewers.contains("NOT EXISTS"));
        String groups=String.join("",SupportLeaderboardSamplingMapper.class.getMethod("groups",LocalDateTime.class).getAnnotation(Select.class).value());
        assertTrue(groups.contains("supervisor_admin_id=g.supervisor_admin_id"));assertTrue(groups.contains("other_o"));
        String members=String.join("",SupportLeaderboardSamplingMapper.class.getMethod("members",LocalDateTime.class).getAnnotation(Select.class).value());
        assertTrue(members.contains("other_m.agent_admin_id=m.agent_admin_id"));}
}
