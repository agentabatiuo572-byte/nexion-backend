package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Map;

/** Read-only cross-domain boundary for the complete Treasury facts rendered by BI L3. */
public interface TreasuryL3FinanceFacade extends DomainFacade {
    Map<String, Object> currentL3FinanceSnapshot();
}
