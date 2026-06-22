package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.List;

public interface FinanceSearchFacade extends DomainFacade {
    List<AdminSearchHit> searchWithdrawals(String keyword, int limit);
}
