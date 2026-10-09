package ffdd.opsconsole.content.mapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportLeaderboardPublicationMapperTest {
    @Test void boundSqlKeepsNoDefinitionFilterOrCountReferenceCurrencyInYesterday() {
        Configuration config=new Configuration();
        new MapperAnnotationBuilder(config,SupportLeaderboardPublicationMapper.class).parse();
        Map<String,Object> parameters=new HashMap<>(); parameters.put("board","firstPayment"); parameters.put("rankMonth","2026-10");
        parameters.put("rankCurrency",null); parameters.put("scope","all"); parameters.put("groupsKey","");
        String sql=config.getMappedStatement(SupportLeaderboardPublicationMapper.class.getName()+".previousDay").getBoundSql(parameters).getSql();
        String where=sql.substring(sql.indexOf("WHERE"));
        assertTrue(where.contains("rank_currency <=> ?")); assertTrue(where.contains("state='COMPLETE'"));
        assertTrue(where.contains("published_at >= ?") && where.contains("published_at < ?")); assertTrue(where.contains("ORDER BY published_at,id"));
        assertFalse(where.contains("definition_version")); assertFalse(where.contains("reference_month")); assertFalse(where.contains("currency="));
        assertFalse(where.contains("stream_key")); assertFalse(sql.contains("${"));
    }
    @Test void mutationsFlushAndReadsDisableCacheWithPointerMutexAndCas() throws Exception {
        for (var method:SupportLeaderboardPublicationMapper.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Select.class)) assertFalse(method.getAnnotation(Options.class).useCache());
            if (method.isAnnotationPresent(Insert.class) || method.isAnnotationPresent(Update.class))
                assertEquals(Options.FlushCachePolicy.TRUE,method.getAnnotation(Options.class).flushCache());
        }
        assertTrue(String.join("",SupportLeaderboardPublicationMapper.class.getMethod("ensurePointer",String.class).getAnnotation(Insert.class).value()).contains("ON DUPLICATE KEY"));
        assertTrue(String.join("",SupportLeaderboardPublicationMapper.class.getMethod("lockPointer",String.class).getAnnotation(Select.class).value()).contains("FOR UPDATE"));
        assertTrue(String.join("",SupportLeaderboardPublicationMapper.class.getMethod("advance",String.class,Long.class,long.class).getAnnotation(Update.class).value()).contains("publication_id <=> #{previousId}"));
        String insert=String.join("",SupportLeaderboardPublicationMapper.class.getMethod("insert",Map.class).getAnnotation(Insert.class).value());
        assertTrue(insert.contains("UTC_TIMESTAMP(6)")); assertFalse(insert.contains("ON DUPLICATE"));
    }
    @Test void ddlOnlyCreatesTwoTablesWithImmutableVersionIdentityUtcMicrosAndJsonIntegrity() throws Exception {
        String ddl=Files.readString(Path.of(System.getProperty("publication.candidate.root","."),"scripts/migrations/20261009_support_leaderboard_publication.sql"));
        assertEquals(2,ddl.split("CREATE TABLE IF NOT EXISTS",-1).length-1);
        assertTrue(ddl.contains("UNIQUE KEY uq_support_leaderboard_view (stream_key,view_version)"));
        assertTrue(ddl.contains("DATETIME(6)") && ddl.contains("JSON_VALID(payload)") && ddl.contains("payload_hash"));
        assertTrue(ddl.contains("FOREIGN KEY (publication_id)"));
        assertFalse(ddl.contains("INSERT ") || ddl.contains("UPDATE ") || ddl.contains("DELETE ") || ddl.contains("ALTER "));
        String startup=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertEquals(1,startup.split("20261009_support_leaderboard_publication.sql",-1).length-1);
    }
}
