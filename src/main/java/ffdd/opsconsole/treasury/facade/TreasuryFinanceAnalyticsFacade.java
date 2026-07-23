package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Map;

/** Read-only bridge exposing the canonical dual-ledger snapshot to BI. */
public interface TreasuryFinanceAnalyticsFacade extends DomainFacade {
    Map<String, Object> currentFinanceSnapshot();
}
