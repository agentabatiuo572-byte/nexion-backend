package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportPaymentAttributionMapperSqlTest {
    @Test void everyObservationIsSingleTableCurrentAndFullHistoryIsNeverEligibilityFiltered() {
        var c=new Configuration();c.addMapper(SupportPaymentAttributionMapper.class);
        for(String method:new String[]{"lockCustomer","assignments","routes","lockAdmin","lockGroup","members","owners","qualifications","findEvidence"}) {
            String sql=c.getMappedStatement(SupportPaymentAttributionMapper.class.getName()+"."+method).getBoundSql(Map.of("id",11L,"factId","PURCHASE:O")).getSql();
            assertThat(sql).as(method).doesNotContain("JOIN","LIMIT","${","nx_admin_role","state='ENABLED'","status='ACTIVE'","ends_at IS NULL");
            assertThat(sql).contains(method.equals("lockCustomer")?"FOR UPDATE":"FOR SHARE");
        }
        assertThat(c.getMappedStatement(SupportPaymentAttributionMapper.class.getName()+".planMembers").getBoundSql(Map.of("id",11L)).getSql()).doesNotContain("FOR SHARE","FOR UPDATE");
        assertThat(c.getMappedStatement(SupportPaymentAttributionMapper.class.getName()+".planGroup").getBoundSql(Map.of("id",11L)).getSql()).doesNotContain("FOR SHARE","FOR UPDATE");
        assertThat(c.getMappedStatement(SupportPaymentAttributionMapper.class.getName()+".databaseUtc").getBoundSql(Map.of()).getSql()).contains("UTC_TIMESTAMP(6)");
    }
    @Test void oneImmutableTableHasNoBackfillOrOverwritePath() throws Exception {
        String migration=Files.readString(Path.of("scripts/migrations/20261008_support_payment_attribution.sql"));
        assertThat(migration).contains("nx_support_payment_attribution","fact_id VARCHAR(128)","PRIMARY KEY","DATETIME(6)","DECIMAL(18,6)","JSON","OLD_SOURCE","UNASSIGNED")
            .doesNotContain("UPDATE ","INSERT ","ALTER ","FOREIGN KEY","DROP ");
        assertThat(migration.split("CREATE TABLE")).hasSize(2);
        var insert=SupportPaymentAttributionMapper.class.getMethod("insert",SupportPaymentAttributionMapper.StoredRow.class).getAnnotation(org.apache.ibatis.annotations.Insert.class);
        assertThat(String.join(" ",insert.value())).contains("INSERT INTO nx_support_payment_attribution").doesNotContain("IGNORE","DUPLICATE","REPLACE");
    }
    @Test void duplicateRecoveryAlwaysReadsTheExactCurrentRowWithoutEitherMybatisCache() {
        var c=new Configuration();c.addMapper(SupportPaymentAttributionMapper.class);
        var statement=c.getMappedStatement(SupportPaymentAttributionMapper.class.getName()+".findEvidence");
        assertThat(statement.isUseCache()).isFalse();assertThat(statement.isFlushCacheRequired()).isTrue();
        var bound=statement.getBoundSql(Map.of("factId","PURCHASE:O-11"));
        assertThat(bound.getSql()).contains("WHERE fact_id=? FOR SHARE").doesNotContain("LIMIT","JOIN","${");
        assertThat(bound.getParameterMappings()).extracting(p->p.getProperty()).containsExactly("factId");
    }
}
