package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MyBatis BoundSql for bounded batch references; not SQL execution. */
class SupportPaymentHistoryBatchSqlTest {
    private Configuration configuration(){var c=new Configuration();new MapperAnnotationBuilder(c,SupportPaymentSourceMapper.class).parse();return c;}
    private String bound(String method,Map<String,Object> parameters){
        var statement=configuration().getMappedStatement(SupportPaymentSourceMapper.class.getName()+"."+method);
        var sql=statement.getBoundSql(parameters);assertTrue(sql.getParameterMappings().size()>0);
        String text=sql.getSql().replaceAll("\\s+"," ").trim();assertFalse(text.toUpperCase(Locale.ROOT).contains("FOR SHARE"));assertFalse(text.toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
        assertTrue(text.startsWith("SELECT "));return text;
    }
    @Test void eachOriginalBeforeProjectionIsPreservedForScopedReferences() {
        for(Source source:List.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY,Source.WALLET_ORDER,Source.TRIAL_CONVERT)) {
            var original=new HashMap<String,Object>();original.put("source",source);original.put("key",source==Source.VIETQR?"D1-VIETQR-one":source==Source.TRIAL_CONVERT?"one:CHARGE":"one");
            String prior=SupportPaymentSourceSql.before(original);String projection=prior.substring(0,prior.indexOf(" WHERE ")).replaceAll("\\s+"," ").trim();
            String actual=bound("historyBeforeBatch",Map.of("source",source,"customerIds",List.of(7L,8L),"keys",List.of("one","two")));
            assertTrue(actual.startsWith(projection+" WHERE "),source+" "+actual);assertTrue(actual.contains("IN ( ? , ? )"),actual);
            assertFalse(actual.contains("is_deleted=0"),"Retained source state must not disappear");
        }
    }
    @Test void allNineSettledQueriesKeepTheOriginalBatchProjectionAndCustomerScope() {
        for(Source source:Source.values())if(source!=Source.FREE_TRIAL &&source!=Source.UNMATCHED_LEDGER && !ffdd.opsconsole.finance.facade.SupportPaymentFacts.genesis(source)) {
            var scope=Map.<String,Object>of("source",source,"customerIds",List.of(7L,8L));
            String base=SupportFundReadMapper.PaymentSourceSql.batch(new HashMap<>(scope));
            var c=configuration();var driver=c.getLanguageDriver(null);
            String original=driver.createSqlSource(c,base,Map.class).getBoundSql(scope).getSql().replaceAll("\\s+"," ").trim();
            var parameters=new HashMap<>(scope);parameters.put("keys",List.of("one","two"));String actual=bound("historySettledBatch",parameters);
            assertTrue(actual.startsWith(original+" AND "),source+" "+actual);
            assertFalse(actual.contains("'one'") ||actual.contains("'two'"));
            var sql=c.getMappedStatement(SupportPaymentSourceMapper.class.getName()+".historySettledBatch").getBoundSql(parameters);
            assertEquals(4,sql.getParameterMappings().size(),source.toString());
        }
    }
    @Test void allMarkerProjectionsRetainExactColumnsAndBoundReferenceKeys() {
        for(var marker:List.of(SupportPaymentSourceMapper.Marker.CREGIS_EVENT,SupportPaymentSourceMapper.Marker.PAYMENT,
                SupportPaymentSourceMapper.Marker.CARD_SETTLEMENT,SupportPaymentSourceMapper.Marker.INTENT,SupportPaymentSourceMapper.Marker.DEVICE)) {
            String prior=switch(marker){case CREGIS_EVENT->SupportPaymentSourceSql.CREGIS_EVENT;case PAYMENT->SupportPaymentSourceSql.PAYMENT;case CARD_SETTLEMENT->SupportPaymentSourceSql.CARD_SETTLEMENT;case INTENT->SupportPaymentSourceSql.INTENT;case DEVICE->SupportPaymentSourceSql.DEVICE;default->throw new AssertionError(marker);};
            var ref=marker==SupportPaymentSourceMapper.Marker.CREGIS_EVENT?Map.<String,Object>of("projectId",100L,"cid",23L):marker==SupportPaymentSourceMapper.Marker.DEVICE?Map.<String,Object>of("id",4L):Map.<String,Object>of("key","one");
            String actual=bound("historyMarkerBatch",Map.of("marker",marker,"customerIds",List.of(7L),"refs",List.of(ref)));
            assertTrue(actual.startsWith(prior.substring(0,prior.indexOf(" WHERE "))+" WHERE "),actual);
            assertFalse(actual.contains("is_deleted=0"),"Marker contradictions must remain visible");
        }
    }
    @Test void emptyScopeUnknownSourcesAndUnknownMarkersCannotProduceQueries() {
        for(String method:List.of("historyBeforeBatch","historySettledBatch")) {
            assertThrows(RuntimeException.class,()->bound(method,Map.of("source",Source.FREE_TRIAL,"customerIds",List.of(7L),"keys",List.of("one"))));
            assertThrows(RuntimeException.class,()->bound(method,Map.of("source",Source.DEPOSIT_ORDER,"customerIds",List.of(),"keys",List.of("one"))));
            assertThrows(RuntimeException.class,()->bound(method,Map.of("source",Source.DEPOSIT_ORDER,"customerIds",List.of(true),"keys",List.of("one"))));
            assertThrows(RuntimeException.class,()->bound(method,Map.of("source",Source.DEPOSIT_ORDER,"customerIds",List.of(7L),"keys",List.of())));
        }
        assertThrows(RuntimeException.class,()->bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.LEDGER,"customerIds",List.of(7L),"refs",List.of(Map.of("key","one")))));
    }
    @Test void markerReferenceTypesAndFieldSetsAreStrict() {
        for(Object invalid:List.of(true,0L,-1L,new java.math.BigDecimal("0.5"),"4")) {
            assertThrows(RuntimeException.class,()->bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.DEVICE,"customerIds",List.of(7L),"refs",List.of(Map.of("id",invalid)))));
            assertThrows(RuntimeException.class,()->bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.CREGIS_EVENT,"customerIds",List.of(7L),"refs",List.of(Map.of("projectId",100L,"cid",invalid)))));
        }
        for(var ref:List.of(Map.of(),Map.of("key",""),Map.of("key",true),Map.of("key","one","id",4L)))
            assertThrows(RuntimeException.class,()->bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.PAYMENT,"customerIds",List.of(7L),"refs",List.of(ref))));
        assertThrows(RuntimeException.class,()->bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.PAYMENT,"customerIds",List.of(7L),"refs",List.of())));
    }
    @Test void sqlTextCannotContainCapturedReferenceValuesOrAcquireLocks() {
        String key="x') OR 1=1 --";
        for(String method:List.of("historyBeforeBatch","historySettledBatch")) {
            String sql=bound(method,Map.of("source",Source.WALLET_ORDER,"customerIds",List.of(7L),"keys",List.of(key)));
            assertFalse(sql.contains(key));assertFalse(sql.contains("OR 1=1"));
        }
        String sql=bound("historyMarkerBatch",Map.of("marker",SupportPaymentSourceMapper.Marker.PAYMENT,"customerIds",List.of(7L),"refs",List.of(Map.of("key",key))));
        assertFalse(sql.contains(key));
    }
}
