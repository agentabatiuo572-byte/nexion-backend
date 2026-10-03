package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;

/** Thrown inside the business transaction: a temporary refusal must remain retryable. */
public class SupportTicketCreationRejectedException extends BizException {
    private final SupportTicketCreationPolicyService.CreationPolicy policy;

    public SupportTicketCreationRejectedException(SupportTicketCreationPolicyService.CreationPolicy policy) {
        super("SUPPORT_TICKET_CREATE_DUPLICATE".equals(policy.reasonCode()) ? 409 : 429, policy.reasonCode());
        this.policy = policy;
    }

    public SupportTicketCreationPolicyService.CreationPolicy policy() { return policy; }
}
