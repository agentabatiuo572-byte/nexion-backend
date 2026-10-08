package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportPaymentSourceMapperSqlTest {
    private final Configuration configuration=new Configuration();
    SupportPaymentSourceMapperSqlTest() { configuration.addMapper(SupportPaymentSourceMapper.class); }
    @Test void candidatesNeverLockMissingRangesAndOnlyCurrentReadsLockKnownPrimaryKeys() {
        for(var method:SupportPaymentSourceMapper.class.getDeclaredMethods()) {
            var statement=configuration.getMappedStatement(SupportPaymentSourceMapper.class.getName()+"."+method.getName());
            var p=parameters(Source.WALLET_ORDER,"order-1");p.put("orderNo","order-1");p.put("id",2L);
            p.put("partition",100L);p.put("cid",23L);p.put("factId","PURCHASE:order-1");p.put("marker",SupportPaymentSourceMapper.Marker.LEDGER);
            p.put("ledger",Map.of("customer",7L,"key","order-1","amount",10,"balanceAfter",20,"remark","original remark"));
            var bound=statement.getBoundSql(p);String sql=bound.getSql();
            assertThat(sql).contains("?").doesNotContain("${","SKIP LOCKED","LIMIT 1","nx_support_agent","nx_support_group","agent_admin_id","group_id","owner_admin_id");
            if(method.getName().equals("insertLedger")) {
                assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
                assertThat(statement.getKeyProperties()).containsExactly("ledger.id");
                assertThat(sql).doesNotContain("IGNORE","DUPLICATE","FOR SHARE");continue;
            }
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(statement.isUseCache()).isFalse();assertThat(statement.isFlushCacheRequired()).isTrue();
            if(method.getName().startsWith("current") || method.getName().equals("settledVietqrCounterpart")) assertThat(sql).contains("FOR SHARE");
            else assertThat(sql).doesNotContain("FOR SHARE");
        }
    }
    @Test void retainedVietqrReceiptReadsItsIndependentProviderTimeEvenAfterSoftDelete() {
        assertThat(bound("before",parameters(Source.VIETQR,"D1-VIETQR-r-1")))
            .contains("received_at providerPaidAt","WHERE reconciliation_no=?").doesNotContain("is_deleted=0","FOR SHARE");
    }
    @Test void allCanonicalFamiliesCurrentReadEveryNecessaryAliasByItsExactId() {
        for(Source source:Source.values()) {
            if(source==Source.FREE_TRIAL || source==Source.UNMATCHED_LEDGER) continue;
            var p=parameters(source,key(source));
            var candidate=bound("settled",p);assertThat(candidate).contains("sourceRootId","sourceLinked","ledgerRecordedAt","succeededAt").doesNotContain("FOR SHARE");
            var current=bound("currentSettled",p);
            assertThat(current).contains("l.id=?","FOR SHARE OF").doesNotContain(" FOR SHARE)");
            String root=switch(source) {case DEPOSIT_ORDER->"d";case CARD_TOPUP->"p";case VIETQR->"r";case HDPAY->"h";default->"o";};
            assertThat(current).contains(root+".id=?","FOR SHARE OF "+root+",l");
            if(source==Source.HDPAY) assertThat(current).contains("i.id=?","FOR SHARE OF h,l,i");
            if(source==Source.TRIAL_CONVERT) assertThat(current).contains("d.id=?","c.id=?","FOR SHARE OF o,l,d,c");
            assertThat(bound("before",p)).doesNotContain("FOR SHARE");
            assertThat(bound("currentBefore",p)).contains(".id=? FOR SHARE OF");
        }
    }
    @Test void currentMarkersAreOnlyFixedExistingPrimaryKeys() {
        for(var marker:SupportPaymentSourceMapper.Marker.values()) {
            var sql=bound("currentMarker",Map.of("marker",marker,"id",3L));
            assertThat(sql).endsWith("WHERE id=? FOR SHARE").doesNotContain("WHERE biz_no=","WHERE order_no=","WHERE payment_no=","WHERE bill_no=");
        }
    }
    @Test void onlyTheAfterVietqrCounterpartChecksMissingBusinessKeyWithACurrentRead() {
        assertThat(bound("ledgers",Map.of("key","intent-1"))).endsWith("WHERE biz_no=?").doesNotContain("FOR SHARE");
        assertThat(bound("settledVietqrCounterpart",Map.of("key","intent-1"))).endsWith("WHERE biz_no=? FOR SHARE")
            .contains("asset currency","direction","customerId");
    }
    @Test void originalFinancialEvidenceUsesItsActualStringPrimaryKeyOnlyAfterCandidateExists() {
        var p=Map.<String,Object>of("factId","PURCHASE:order-1");
        assertThat(bound("originalSourceProof",p)).contains("source_fact_json","'$.beforeSource'","capture_schema_version","WHERE fact_id=?").doesNotContain("FOR SHARE");
        assertThat(bound("currentSourceProof",p)).endsWith("WHERE fact_id=? FOR SHARE");
    }
    @Test void historyLedgerBatchRequiresBothExplicitCustomerAndExistingLedgerIdsWithoutLocks() {
        var p=Map.<String,Object>of("customerIds",List.of(7L,8L),"ledgerIds",List.of(201L,202L));
        var statement=configuration.getMappedStatement(SupportPaymentSourceMapper.class.getName()+".historyLedgers");
        var sql=statement.getBoundSql(p);
        assertThat(sql.getSql()).contains("FROM nx_wallet_ledger WHERE user_id IN","AND id IN",
            "biz_no businessId","is_deleted deleted","created_at successAt").doesNotContain("FOR SHARE","FOR UPDATE","${");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
            .containsExactly("__frch_customer_0","__frch_customer_1","__frch_ledger_2","__frch_ledger_3");
        assertThat(statement.isUseCache()).isFalse();assertThat(statement.isFlushCacheRequired()).isTrue();
    }
    @Test void retainedFinancialRootsIncludeMoneyEvenAfterSoftDeletion() {
        for(Source source:List.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP)) {
            assertThat(bound("before",parameters(source,key(source))))
                .contains(" amount").doesNotContain("is_deleted=0","FOR SHARE");
        }
        assertThat(bound("before",parameters(Source.DEPOSIT_ORDER,key(Source.DEPOSIT_ORDER)))).contains("d.asset currency");
        assertThat(bound("before",parameters(Source.CARD_TOPUP,key(Source.CARD_TOPUP))))
            .contains("currency,provider,provider_payment_id providerPaymentId");
    }
    @Test void freshInsertKeepsTheOriginalMoneyTupleAndSourceTimestampPrecision() {
        for(Source source:Source.values()) {
            if(source==Source.FREE_TRIAL || source==Source.UNMATCHED_LEDGER) continue;
            var p=parameters(source,key(source));p.put("ledger",Map.of("customer",7L,"key",key(source),"amount",10,"balanceAfter",20,"remark","original remark"));
            var sql=bound("insertLedger",p);
            assertThat(sql).startsWith("INSERT INTO nx_wallet_ledger").contains("'"+SupportPaymentSourceSql.ledgerType(source)+"'","'USDT'","'"+SupportPaymentSourceSql.ledgerDirection(source)+"'")
                .doesNotContain("IGNORE","DUPLICATE","ROUND(","COALESCE(");
            if(source==Source.DEPOSIT_ORDER) assertThat(sql).doesNotContain("created_at","updated_at","NOW(");
            else if(source==Source.WALLET_ORDER) assertThat(sql).contains("NOW(6),NOW(6)");
            else assertThat(sql).contains("NOW(),NOW()");
            assertThat(sql).contains(source==Source.TRIAL_CONVERT?"'POSTED'":"'SUCCESS'");
        }
    }
    @Test void cardOptionalAdmissionStaysNonlockingAndOrderConfirmationHasItsOwnPkRead() {
        assertThat(bound("currentSettled",parameters(Source.CARD_TOPUP,"card-1")))
            .contains("nx_topup_card_admission","FOR SHARE OF p,l").doesNotContain("a.is_deleted=0 FOR SHARE","c.is_deleted=0 FOR SHARE");
        assertThat(bound("currentSettled",parameters(Source.WALLET_ORDER,"order-1")))
            .contains("o.payment_no paymentNo","o.paid_at succeededAt","6 fractionalSecondDigits").doesNotContain("SELECT COUNT","SELECT MIN","nx_payment_record");
    }
    @Test void trialAliasAndUnsupportedSourcesCannotBecomeArbitrarySql() {
        assertThat(bound("before",parameters(Source.TRIAL_CONVERT,"USER:7"))).contains("user_id=?").doesNotContain("FOR SHARE");
        assertThatThrownBy(()->SupportPaymentSourceSql.settled(parameters(Source.FREE_TRIAL,"anything"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupportPaymentSourceSql.settled(parameters(Source.VIETQR,"wrong-prefix"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupportPaymentSourceSql.insertLedger(parameters(Source.UNMATCHED_LEDGER,"anything"))).isInstanceOf(IllegalArgumentException.class);
    }
    private String bound(String name,Map<String,Object> p) {
        return configuration.getMappedStatement(SupportPaymentSourceMapper.class.getName()+"."+name).getBoundSql(new HashMap<>(p)).getSql();
    }
    private String key(Source source) {
        return switch(source) {case DEPOSIT_ORDER->"CR-23";case VIETQR->"D1-VIETQR-receipt-1";case TRIAL_CONVERT->"claim-1:CHARGE";case ORDER_REFUND->"E4-REFUND-order-1";default->"order-1";};
    }
    private HashMap<String,Object> parameters(Source source,String key) {
        var p=new HashMap<String,Object>();p.put("source",source);p.put("key",key);p.put("customerId",7L);p.put("customerIds",List.of(7L));
        p.put("ids",Map.of("sourceRootId",1L,"ledgerId",201L,"intentId",2L,"deviceId",3L,"claimId",4L));p.put("ledgerIds",List.of(201L));return p;
    }
}
