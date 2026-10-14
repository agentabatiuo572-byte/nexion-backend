package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Four-argument production query plus actual HTTP and source readers; legacy suites stay unchanged. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_AGGREGATE_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportAnalyticsAggregateRuntimeTest extends SupportAnalyticsRuntimeFixture {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r){SupportEnhancementPreparationTest.isolatedBoundary(r);}
    @Autowired SupportFundsReadFacade funds;

    @Test void realInjectedFundsReaderKeepsExactTwoCurrencySelectedTotalsAndMissingWalletUnknown()throws Exception {
        long a=customer(false),b=customer(false);bind(a,agent);bind(b,agent);
        wallet(a,"999999999999.123456","0.000001");wallet(b,"1.000001","2.000002");
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        var source=tx.execute(status->funds.readCurrent(List.of(a,b,a)));
        assertThat(source).isNotNull();assertThat(source.customerIds()).containsExactlyElementsOf(new TreeSet<>(List.of(a,b)));
        nativeObservations.put("actualInjectedFundsSnapshot",source);
        assertThat(source.wallets().state().name()).isEqualTo("READY");
        for(long actor:List.of(agent,manager,boss)) {
            String scope=actor==agent?"":"&groupId="+group;
            JsonNode result=analytics(actor,"view=CUSTOMERS"+scope+"&keyword="+run+"&pageSize=1");
            JsonNode f=result.path("funds");assertThat(f.path("balanceStatus").asText()).isEqualTo("READY");
            assertThat(f.path("sourceObservationStatus").asText()).isEqualTo("COMPLETE");
            assertThat(f.path("customerCount").asLong()).isEqualTo(2);assertThat(result.path("records")).hasSize(1);
            assertMoney(currency(f,"USDT").path("confirmedAmount"),"1000000000000.123457");
            assertMoney(currency(f,"NEX").path("confirmedAmount"),"2.000003");
            assertThat(f.path("historicalEnvironmentStatus").asText()).isEqualTo("UNKNOWN");
            assertThat(f.path("eventOwnershipStatus").asText()).isEqualTo("UNKNOWN");
            assertThat(f.path("withdrawalReadScope").asText()).isEqualTo("CURRENT_CUSTOMER_RAW_ROWS");
            JsonNode repeat=analytics(actor,"view=CUSTOMERS"+scope+"&keyword="+run+"&pageSize=2");
            assertThat(repeat.path("funds")).isEqualTo(f);
        }
        long missing=customer(false);bind(missing,agent);
        JsonNode partial=analytics(agent,"keyword="+run).path("funds");
        assertThat(partial.path("balanceStatus").asText()).isEqualTo("PARTIAL");
        assertThat(partial.path("walletReadReasons").toString()).contains("WALLET_MISSING");
        for(var amount:partial.path("balances"))assertThat(amount.path("confirmedAmount").isNull()).isTrue();
        assertThat(fresh("SELECT usdt_available,nex_available FROM nx_user_wallet WHERE user_id=?",a)).singleElement().satisfies(row->{assertThat((BigDecimal)row.get("usdt_available")).isEqualByComparingTo("999999999999.123456");assertThat((BigDecimal)row.get("nex_available")).isEqualByComparingTo("0.000001");});
        checked("Autowiring uses actual four-argument funds facade and real HTTP. Full selected-customer balance exceeds one-page total; exact USDT/NEX, repeated size-independent aggregate, missing wallet is PARTIAL/null confirmed; raw withdrawals keep history/ownership UNKNOWN");
    }

    @Test void crossGroupFourNodeInvitationIsAggregateOnlyAndCurrentScopeNeverExpands()throws Exception {
        long a=customer(false),b=customer(false),c=customer(false),d=customer(false);
        bind(a,agent);for(long id:List.of(b,c,d))bind(id,foreignAgent);
        for(var pair:List.of(new long[]{b,a},new long[]{c,b},new long[]{d,c})){
            requireCustomer(pair[0]);requireCustomer(pair[1]);assertThat(jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=? AND nickname=?",pair[1],pair[0],run)).isEqualTo(1);
        }
        for(long actor:List.of(agent,manager)){
            JsonNode result=analytics(actor,"keyword="+run);assertThat(ids(result,"customerId")).containsExactly(a);
            JsonNode invitation=result.path("records").get(0).path("invitations");
            assertThat(invitation.path("direct").path("observed").asLong(-1)).isEqualTo(1);
            assertThat(invitation.path("descendants").path("observed").asLong(-1)).isEqualTo(3);
            assertThat(invitation.path("descendants").path("confirmed").asLong(-1)).isEqualTo(3);
            for(long foreign:List.of(b,c,d)) {
                assertThat(result.toString()).doesNotContain("\"customerId\":\""+foreign+"\"","/customers/"+foreign);
                rejected(request("GET","/api/admin/content/support-workbench/customers/"+foreign,actor,null,null),403,404);
            }
            for(String view:List.of("FINANCE","DEVICES","ACTIVITY")) {
                JsonNode other=analytics(actor,"view="+view+"&currency=USDT&keyword="+run);
                for(var row:other.path("records"))assertThat(row.path("customerId").asLong()).isEqualTo(a);
            }
        }
        JsonNode platform=analytics(boss,"keyword="+run);assertThat(ids(platform,"customerId")).containsExactlyInAnyOrder(a,b,c,d);
        assertThat(platform.path("selectedCustomerCount").asLong()).isEqualTo(4);
        checked("Actual A→B→C→D SQL; direct1/all3 beyond service inheritance settings, cross-group aggregate without foreign identity/profile link or direct access. Platform unique current customers4; no sum of row descendant counts");
    }

    @Test void currentTransferChangesBothRolesCurrentFundsWithoutMovingOldScopeAuthority()throws Exception {
        long customer=customer(false);bind(customer,agent);wallet(customer,"10.123456","20.654321");
        JsonNode old=analytics(manager,"keyword="+run);assertThat(ids(old,"customerId")).containsExactly(customer);
        bind(customer,foreignAgent);
        assertThat(ids(analytics(manager,"keyword="+run),"customerId")).isEmpty();
        JsonNode current=analytics(foreignManager,"keyword="+run);assertThat(ids(current,"customerId")).containsExactly(customer);
        assertMoney(currency(current.path("funds"),"USDT").path("confirmedAmount"),"10.123456");
        assertMoney(currency(current.path("funds"),"NEX").path("confirmedAmount"),"20.654321");
        rejected(request("GET","/api/admin/content/support-workbench/customers/"+customer,manager,null,null),403,404);
        checked("Committed production transfer and independent binding readback: old manager loses current customer/funds/detail, new manager receives same exact two-currency balances. Event-period preservation remains existing native-suite responsibility, not signed here");
    }
    private static JsonNode currency(JsonNode funds,String currency){for(var row:funds.path("balances"))if(row.path("currency").asText().equals(currency))return row;throw new AssertionError("Required currency missing: "+currency);}
    private static void assertMoney(JsonNode actual,String expected){assertThat(actual.isTextual()).isTrue();assertThat(new BigDecimal(actual.asText())).isEqualByComparingTo(expected);}
}
