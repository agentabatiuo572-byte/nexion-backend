package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.List;

public interface ContentSearchFacade extends DomainFacade {
    List<AdminSearchHit> searchSupportTickets(String keyword, int limit);

    List<AdminSearchHit> searchConversations(String keyword, int limit);
}
