package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Uses the existing clone fence and retained-facts lifecycle; the clone is cleaned as one unit. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_COMMITTED_ENABLED",matches="true")
class SupportAccessCommittedRuntimeTest extends SupportNetContributionCommittedRuntimeTest {
    @Test void actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty() throws Exception {
        long own=bornCustomer(),foreign=bornCustomer();bind(own,agent);bind(foreign,foreignAgent);
        for(long actor:List.of(agent,manager,boss)) {
            var result=analytics(actor,"view=CUSTOMERS"+(actor==agent?"":"&groupId="+group)+"&keyword="+run);
            assertThat(ids(result,"customerId")).containsExactly(own);
            assertThat(result.path("scopeSummary").path("mode").asText()).isEqualTo(actor==agent?"PERSONAL":actor==manager?"MANAGED":"ALL");
        }
        for(long actor:List.of(foreignAgent,foreignManager))
            assertThat(ids(analytics(actor,"view=CUSTOMERS&keyword="+run),"customerId")).containsExactly(foreign);
        assertThat(ids(analytics(boss,"view=CUSTOMERS&keyword="+run),"customerId")).containsExactlyInAnyOrder(own,foreign);
        var empty=analytics(ownerless,"view=CUSTOMERS&keyword="+run);
        assertThat(empty.path("records")).isEmpty();assertThat(empty.path("selectedCustomerCount").asLong()).isZero();
        deny(manager,"groupId="+foreignGroup+"&pageNum=2&expectedVersion="+stale(),403,404);
        deny(agent,"agentId="+foreignAgent,422);deny(agent,"groupId="+foreignGroup,422);
        deny(agent,"customerId="+foreign,422);deny(agent,"mode=ALL",422);
        deny(manager,"view=CUSTOMERS&view=AGENTS",422);deny(manager,"currency=BTC",422);
        deny(manager,"pageNum=2",422);
        checked("Two real supervisor groups, two dedicated agents and global admin have exact HTTP customer sets; ownerless is empty and forged scope is rejected. AUTH facts retained for final clone cleanup.");
    }

    @Test void oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion() throws Exception {
        long own=bornCustomer();bind(own,agent);
        analytics(agent,"keyword="+run);String originalToken=token(agent);
        bind(own,foreignAgent);
        rejected(request("GET","/api/admin/content/support-workbench/customers/"+own,agent,null,null),403,404);
        assertThat(token(agent)).isEqualTo(originalToken);
        deny(manager,"groupId="+foreignGroup+"&pageNum=2&expectedVersion="+stale(),403,404);
        assertThat(ids(analytics(foreignAgent,"keyword="+run),"customerId")).containsExactly(own);
        assertThat(ids(analytics(agent,"keyword="+run),"customerId")).isEmpty();
        checked("Actual HTTP transfer revokes the unchanged old bearer for detail and list; the new dedicated agent can read. Customer and audit facts retained for final clone cleanup.");
    }
}
