package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.*;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportFundReadMapperSqlTest {
    private final Configuration config=new Configuration();
    SupportFundReadMapperSqlTest(){config.addMapper(SupportFundReadMapper.class);}
    @Test void allReadStatementsArePlainParameterizedSelectsAndBypassBothCaches(){
        for(String name:List.of("wallets","raw","operationIds","operations","parties","legs","withdrawals","ledgers","withdrawalAudits","bankReservations","withdrawalAttempts","paymentLedgers","earnings","paymentAudits","trialSources")) {
            var statement=config.getMappedStatement(SupportFundReadMapper.class.getName()+"."+name);
            var bound=statement.getBoundSql(Map.of("ids",List.of(7L,8L),"walletIds",List.of(70L,80L),"customerIds",List.of(7L,8L)));
            String sql=bound.getSql().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
            assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());assertFalse(statement.isUseCache());assertTrue(statement.isFlushCacheRequired());
            assertFalse(sql.contains("FOR UPDATE"));assertFalse(sql.contains("FOR SHARE"));assertFalse(sql.contains("${"));assertTrue(sql.contains("?"));
        }
    }
    @Test void allNinePaymentProjectionsRetainExactCustomerScopeAndDropOnlySingleKeyPredicate(){
        for(var source:Source.values()) {
            if(source==Source.FREE_TRIAL ||source==Source.UNMATCHED_LEDGER
                    ||source==Source.GENESIS_PRIMARY ||source==Source.GENESIS_SECONDARY)continue;
            var parameters=Map.<String,Object>of("source",source,"customerIds",List.of(7L,8L));
            var bound=config.getMappedStatement(SupportFundReadMapper.class.getName()+".paymentSources").getBoundSql(parameters);
            String sql=bound.getSql().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
            assertTrue(sql.contains(" IN ("),sql);assertFalse(sql.contains("FOR UPDATE"));assertFalse(sql.contains("FOR SHARE"));
            assertFalse(sql.contains("${"));assertFalse(sql.contains("#{KEY}"));assertFalse(sql.contains("#{RAWKEY}"));
            assertFalse(bound.getParameterMappings().isEmpty());
            for(var p:bound.getParameterMappings())assertTrue(p.getProperty().startsWith("__frch_"),p.getProperty());
            for(var p:bound.getParameterMappings())assertTrue(Set.of(7L,8L).contains(bound.getAdditionalParameter(p.getProperty())));
            var request=new HashMap<String,Object>(parameters);request.put("key",switch(source) {case VIETQR->"D1-VIETQR-BATCH";case TRIAL_CONVERT->"BATCH:CHARGE";case ORDER_REFUND->"E4-REFUND-BATCH";default->"BATCH";});
            String original=SupportPaymentSourceSql.settled(request).stripTrailing();String actual=SupportFundReadMapper.PaymentSourceSql.batch(parameters);
            int extra=original.lastIndexOf(" AND ");assertTrue(extra>0);assertEquals(original.substring(0,extra)+"</script>",actual);
        }
    }
    @Test void unknownOrMissingPaymentSourceIsRejected(){
        for(var source:List.of(Source.FREE_TRIAL,Source.UNMATCHED_LEDGER,Source.GENESIS_PRIMARY,Source.GENESIS_SECONDARY))assertThrows(IllegalArgumentException.class,()->SupportFundReadMapper.PaymentSourceSql.batch(Map.of("source",source,"customerIds",List.of(7L))));
        assertThrows(IllegalArgumentException.class,()->SupportFundReadMapper.PaymentSourceSql.batch(Map.of("customerIds",List.of(7L))));
    }
    @Test void currentSchemaGateIsAPlainReadAndContainsActualTriggerBodiesNotProofFlags(){
        var statement=config.getMappedStatement(SupportFundReadMapper.class.getName()+".schemaReady");
        assertEquals("SELECT nx_support_fund_schema_ready_v1()",statement.getBoundSql(Map.of()).getSql());
        assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());assertFalse(statement.isUseCache());assertTrue(statement.isFlushCacheRequired());
        String sql=SupportFundReadSchemaSql.SQL;
        assertTrue(sql.stripLeading().startsWith("SELECT ("));assertFalse(sql.contains("@fund"));assertFalse(sql.contains("CREATE TRIGGER"));
        assertFalse(sql.contains("FOR UPDATE"));assertFalse(sql.contains("FOR SHARE"));
        assertTrue(sql.contains("BINARY ACTION_STATEMENT=BINARY 'BEGIN"));assertTrue(sql.contains("SUPPORT_FUND_RECORD_IMMUTABLE"));
        assertTrue(sql.contains("REFERENTIAL_CONSTRAINTS"));assertTrue(sql.contains("CHECK_CONSTRAINTS"));assertTrue(sql.contains("IS_VISIBLE='YES'"));
    }
}
