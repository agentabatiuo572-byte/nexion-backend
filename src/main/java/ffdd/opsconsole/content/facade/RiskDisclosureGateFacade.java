package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.shared.api.ApiResult;

/** Server-side compliance gate for real business operations. */
public interface RiskDisclosureGateFacade {
    ApiResult<Void> checkUserGate(Long userId, String actionKey, String businessFlowId);
}
