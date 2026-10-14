package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportFundReadMapper;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportFundEarningsSqlTest {
    Configuration config(){var c=new Configuration();c.addMapper(SupportFundReadMapper.class);c.addMapper(EarningsReleaseMapper.class);return c;}
    @Test void owningSourceBatchReadsHaveFixedExplicitScopeNoRowLocksOrHiddenHistoryFilters() {
        var c=config();
        for(String method:List.of("earningsSources","earningsRecoveries","earningsAppliedProofs","earningsAudits")) {
            var statement=c.getMappedStatement(SupportFundReadMapper.class.getName()+"."+method);
            for(int count:List.of(1,2,20)) {
                var bound=statement.getBoundSql(Map.of("ids",java.util.stream.LongStream.range(1,count+1).boxed().toList()));
                String sql=bound.getSql().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
                assertTrue(sql.startsWith("SELECT "));assertEquals(count,bound.getParameterMappings().size());
                assertFalse(sql.contains("FOR UPDATE"));assertFalse(sql.contains("FOR SHARE"));assertFalse(sql.contains("LIMIT "));
                assertFalse(sql.contains("IS_DELETED=0"));assertFalse(sql.contains("STATUS='ACTIVE'"));assertFalse(sql.contains("RELEASED_AT IS NULL"));
                assertTrue(sql.contains("USER_ID IN") ||sql.contains("USER_ID IN "));
                assertFalse(statement.isUseCache());
            }
        }
    }
    @Test void predecessorAndSourceWriterReadsRemainCurrentLockingReadsAfterWalletLock() {
        var c=config();
        for(String method:List.of("fundEntry","fundRecovery","fundAppliedProof","fundReleaseAudit","previousFundOperation")) {
            var statement=c.getMappedStatement(EarningsReleaseMapper.class.getName()+"."+method);
            String sql=statement.getBoundSql(Map.of("entryNo","ER-7","recoveryNo","RC-7","proofId","PROOF-7","action","K1_EARNINGS_MANUALLY_RELEASED","userId",7L)).getSql().toUpperCase(Locale.ROOT);
            assertTrue(sql.endsWith("FOR SHARE"));assertFalse(statement.isUseCache());
            if(method.equals("previousFundOperation")){assertTrue(sql.contains("CUSTOMER_ID=?"));assertTrue(sql.contains("ORDER BY OPERATION_ID DESC LIMIT 1"));}
        }
    }
    @Test void sourceTimestampPrecisionComesFromExactOwningTablesAndColumns() {
        var sql=config().getMappedStatement(SupportFundReadMapper.class.getName()+".earningsTimePrecisions").getBoundSql(Map.of()).getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("information_schema.columns"));assertTrue(sql.contains("table_schema=database()"));
        assertTrue(sql.contains("nx_earnings_release_entry"));assertTrue(sql.contains("released_at"));assertTrue(sql.contains("nx_earnings_source_recovery"));
        assertFalse(sql.contains("for share"));assertFalse(sql.contains("for update"));
    }
}
