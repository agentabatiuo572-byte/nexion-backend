package ffdd.opsconsole.common.boundary;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Receipt;

/** E6 owns global policy commands; C5 recovery routes consume only this public boundary. */
public interface PhoneReplacementPolicyOperations extends DomainFacade {
    Receipt operation(String operationId);
    Receipt operationByKey(String commandType, String idempotencyKey);
}
