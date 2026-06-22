package ffdd.opsconsole.user.facade;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.List;

public interface UserSearchFacade extends DomainFacade {
    List<AdminSearchHit> searchAdminUsers(String keyword, int limit);
}
