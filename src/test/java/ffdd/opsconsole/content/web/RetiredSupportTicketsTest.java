package ffdd.opsconsole.content.web;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class RetiredSupportTicketsTest {
    @Test void productionAndSandboxTicketPathsAreRetiredWithoutRetiringChatAuthorizationOrSeatCapacity() {
        for(String prefix:new String[]{"/api/app/support","/api/app/support/acceptance","/api/admin/content","/api/admin/content/support/acceptance"}) {
            for(String suffix:new String[]{"/tickets","/tickets/TK-1","/tickets/TK-1/replies","/conversations/CV-1/ticket"}) assertThat(RetiredSupportTickets.retired(prefix+suffix)).as(prefix+suffix).isTrue();
        }
        for(String path:new String[]{"/api/app/support/sla-targets","/api/admin/content/knowledge/sla/technical"}) assertThat(RetiredSupportTickets.retired(path)).isTrue();
        for(String path:new String[]{"/api/app/support/realtime-ticket","/api/admin/content/conversations/realtime-ticket","/api/admin/content/tickets/load-config","/api/admin/content/tickets/load-config/rebalance","/api/admin/content/conversations/CV-1/replies"}) assertThat(RetiredSupportTickets.retired(path)).as(path).isFalse();
    }
}
