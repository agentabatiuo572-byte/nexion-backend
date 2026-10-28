package ffdd.opsconsole.common.boundary;

import java.util.List;
import java.util.Map;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;

/** Installation model locks for C5 support. The C5 adapter authorizes the administrator and customer first. */
public interface PhoneModelLockContract extends DomainFacade {
    List<Map<String,Object>> locks(Account account);
    /**
     * Releases the lock of the installation revision support looked at. Idempotent: an installation
     * already released, or with no model to lock, is returned unchanged; a newer calibration is a conflict.
     */
    Map<String,Object> release(Account account, String installationId, long expectedRevision, long actor, String reason, String key);
}
