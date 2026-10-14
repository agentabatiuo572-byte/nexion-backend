package ffdd.opsconsole.market.mapper;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
class SupportGenesisSourceMapperTest {
    @Test void renderedSqlHasExplicitReversePartiesOriginalTimeAndNoLockOrHistoricalStatusFilter() {
        var config=new Configuration();new MapperAnnotationBuilder(config,SupportGenesisSourceMapper.class).parse();
        String prefix=SupportGenesisSourceMapper.class.getName()+".";
        var sql=config.getMappedStatement(prefix+"ordersByCustomers").getBoundSql(Map.of("ids",List.of(3L,7L)));
        assertThat(sql.getSql()).contains("o.user_id IN","OR o.seller_user_id IN","o.paid_at paidAt","o.completed_at completedAt","LEFT JOIN nx_user");
        assertThat(sql.getSql().toUpperCase()).doesNotContain("FOR UPDATE","FOR SHARE","O.IS_DELETED=0","O.STATUS=");
        assertThat(sql.getParameterMappings()).hasSize(4);
        var ledger=config.getMappedStatement(prefix+"ledgers").getBoundSql(Map.of("refs",List.of("A","A-BUY","A-SELL")));
        assertThat(ledger.getParameterMappings()).hasSize(3);assertThat(ledger.getSql()).contains("created_at successAt","balance_after balanceAfter");
        assertThat(ledger.getSql().toUpperCase()).doesNotContain("FOR UPDATE","FOR SHARE","IS_DELETED=0");
    }
}
