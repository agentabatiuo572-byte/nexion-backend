package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** Pure SQL-fence contract; no Spring, datasource, HTTP or fixture mutation. */
class SupportGroupExactFactsTest {
    @Test void compressedFactsPreserveEveryOriginalByteIncludingNullAndDecimalData() throws Exception {
        byte[] original="{\"before\":[{\"id\":6270,\"value\":1.000001,\"ends_at\":null,\"name\":\"客服\"}],\"after\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed=SupportGroupRuntimeFixtures.ExactFacts.compressProof(original);
        try(var input=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))){assertThat(input.readAllBytes()).isEqualTo(original);}
    }
    @Test void repeatedFullFactRowsAreStoredCompactlyWithoutDroppingRows() throws Exception {
        byte[] original=("{\"id\":6270,\"balance\":749.000000,\"ends_at\":null}\n".repeat(20_000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.compressProof(original).length).isLessThan(original.length/20);
    }
    @Test void completeRowFenceIncludesPrimaryKeyAndRealNullsInStableArgumentOrder() {
        var row=new LinkedHashMap<String,Object>();row.put("id",17L);row.put("operation_id",null);row.put("ends_at",null);row.put("version",3L);
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.fence(row)).isEqualTo("`id` <=> ? AND `operation_id` <=> ? AND `ends_at` <=> ? AND `version` <=> ?");
        assertThat(row.values().toArray()).containsExactly(17L,null,null,3L);
    }
    @Test void unsupportedSqlIdentifiersAndEmptyFencesFailClosed() {
        var row=new LinkedHashMap<String,Object>();row.put("id` OR 1=1",4L);
        assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.fence(row)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.fence(new LinkedHashMap<>())).isInstanceOf(AssertionError.class);
    }
    @Test void birthAndPoolUseTheirActualCustomerPrimaryKeyWhileWalletUsesItsId() {
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.primary("nx_support_payment_history_birth")).isEqualTo("customer_id");
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.primary("nx_support_binding_pool")).isEqualTo("customer_id");
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.primary("nx_user_wallet")).isEqualTo("id");
    }
    @Test void oneCleanupFailureCannotSkipLaterDomainsOrDiscardOriginalExceptions() {
        var ran=new java.util.ArrayList<Integer>();var first=new IllegalStateException("first original failure");var second=new AssertionError("second original failure");
        assertThatThrownBy(()->SupportObjectEvidenceLedger.cleanupIndependently(
                ()->{ran.add(1);throw first;},()->ran.add(2),()->{ran.add(3);throw second;},()->ran.add(4)))
                .isInstanceOf(AssertionError.class).satisfies(failure->assertThat(failure.getSuppressed()).containsExactly(first,second));
        assertThat(ran).containsExactly(1,2,3,4);
    }
    @Test void qualificationDisableAndReenableCloseTheirOwnHistoryWithoutChangingTheOldStateOrVersion() {
        for(String state:java.util.List.of("ENABLED","DISABLED")){
            var old=new LinkedHashMap<String,Object>();old.put("id",19L);old.put("admin_id",5L);old.put("qualification_kind","SERVICE");old.put("state",state);old.put("version",2L);old.put("ends_at",null);old.put("current_admin_id",5L);
            var closed=new LinkedHashMap<>(old);closed.put("ends_at",java.sql.Timestamp.valueOf("2026-10-09 14:00:00"));closed.put("current_admin_id",null);
            assertThatCode(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_account_qualification_history",old,closed,"GROUP_QUALIFICATION")).doesNotThrowAnyException();
            closed.put("state",state.equals("ENABLED")?"DISABLED":"ENABLED");
            assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_account_qualification_history",old,closed,"GROUP_QUALIFICATION")).isInstanceOf(AssertionError.class);
        }
    }
    @Test void ownerTransferRequiresItsSpecificOperationAndCannotMasqueradeAsNewGroupCreation() {
        var before=new LinkedHashMap<String,Object>();before.put("id",21L);before.put("supervisor_admin_id",7L);before.put("version",3L);before.put("updated_at",java.sql.Timestamp.valueOf("2026-10-09 14:00:00"));
        var after=new LinkedHashMap<>(before);after.put("supervisor_admin_id",8L);after.put("version",4L);after.put("updated_at",java.sql.Timestamp.valueOf("2026-10-09 14:00:01"));
        assertThatCode(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_group",before,after,"GROUP_OWNER")).doesNotThrowAnyException();
        assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_group",before,after,"GROUP_MEMBER")).isInstanceOf(AssertionError.class);
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.ownerKind("GROUP_OWNER")).isEqualTo("OWNER");
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.ownerKind("GROUP_CREATE")).isEqualTo("CREATE");
        assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.ownerKind("GROUP_AUTHENTICATED_COMMANDS")).isInstanceOf(AssertionError.class);
    }
    @Test void routeEndUsesItsActualCustomerGeneratedColumnAndKeepsPrimaryKeyAndVersion() {
        var before=new LinkedHashMap<String,Object>();before.put("id",23L);before.put("customer_id",9L);before.put("current_customer_id",9L);before.put("version",1L);before.put("ends_at",null);
        var after=new LinkedHashMap<>(before);after.put("ends_at",java.sql.Timestamp.valueOf("2026-10-09 14:00:00"));after.put("current_customer_id",null);
        assertThatCode(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_customer_route_history",before,after,"CUSTOMER_TRANSFER_HTTP")).doesNotThrowAnyException();
        after.put("customer_id",10L);assertThatThrownBy(()->SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_customer_route_history",before,after,"CUSTOMER_TRANSFER_HTTP")).isInstanceOf(AssertionError.class);
    }
}
