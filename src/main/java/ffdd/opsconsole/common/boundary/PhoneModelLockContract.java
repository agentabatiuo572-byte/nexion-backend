package ffdd.opsconsole.common.boundary;

import java.util.List;
import java.util.Map;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;

/** Installation model locks for C5 support. The C5 adapter authorizes the administrator and customer first. */
public interface PhoneModelLockContract extends DomainFacade {
    List<Map<String,Object>> locks(Account account);
    /** Idempotent: an installation without a stored lock is returned unchanged. */
    Map<String,Object> release(Account account, String installationId, long actor, String reason, String key);
}
