package ffdd.opsconsole.content.mapper;

import java.util.*;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportLeaderboardMapperTest {
    private static String sql(String name) {try {return String.join(" ",SupportLeaderboardMapper.class.getMethod(name).getAnnotation(Select.class).value()).toUpperCase(Locale.ROOT);} catch(ReflectiveOperationException e) {throw new AssertionError(e);}}
    @Test void actualMyBatisBuildsAllReadStatementsWithoutPrivateScopeOrPaging() {
        var configuration=new Configuration();configuration.addMapper(SupportLeaderboardMapper.class);
        for(String name:List.of("nowUtc","accounts","qualifications","memberships","groups","currentBindings","productionCustomers","attributions","attributionProofs")) {
            var statement=configuration.getMappedStatement(SupportLeaderboardMapper.class.getName()+"."+name);
            assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());var bound=statement.getBoundSql(null);
            assertTrue(bound.getParameterMappings().isEmpty());assertTrue(bound.getSql().strip().startsWith("SELECT"));
            assertFalse(sql(name).contains(" LIMIT "));assertFalse(sql(name).contains("READSCOPE"));
            assertFalse(sql(name).contains("REQUESTEDAGENT"));assertFalse(sql(name).contains("CURRENTADMIN"));
        }
    }
    @Test void sourcePreservesFullQualificationMembershipAndCanonicalProofTuples() {
        assertTrue(sql("qualifications").contains("QUALIFICATION_KIND='SERVICE'"));assertFalse(sql("qualifications").contains("ENDS_AT IS NULL"));
        assertTrue(sql("qualifications").contains("VERSION"));assertFalse(sql("memberships").contains("ENDS_AT IS NULL"));
        assertTrue(sql("attributionProofs").contains("SOURCE_PARTITION"));assertTrue(sql("attributionProofs").contains("CAPTURE_DB_UTC"));
        assertTrue(sql("attributionProofs").contains("SOURCE_FACT_JSON"));assertTrue(sql("attributionProofs").contains("ATTRIBUTION_EVIDENCE_JSON"));
        assertTrue(sql("attributions").contains("SUCCESS_TIME_FIELD"));assertTrue(sql("attributions").contains("FRACTIONAL_SECOND_DIGITS"));
    }
    @Test void currentBindingsAndLifetimeCustomersUseSeparatePhysicalPopulationAndPublicNamesNeverReadContacts() {
        assertTrue(sql("currentBindings").contains("A.STATUS='ACTIVE'"));assertTrue(sql("currentBindings").contains("U.IS_DELETED=0"));
        assertTrue(sql("currentBindings").contains("A.STARTS_AT"));assertTrue(sql("currentBindings").contains("A.ENDS_AT"));
        assertFalse(sql("productionCustomers").contains("IS_DELETED"));assertTrue(sql("productionCustomers").contains("SANDBOX=0"));
        assertTrue(sql("accounts").contains("A.NICKNAME"));for(String forbidden:List.of("USERNAME","EMAIL","PHONE","OBJECT_KEY"))assertFalse(sql("accounts").contains(forbidden));
        assertTrue(sql("accounts").contains("ATTACHED_ADMIN_ID"));assertFalse(sql("accounts").contains("SUPER_ADMIN') OR"));
    }
}
