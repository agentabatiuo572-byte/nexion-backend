package ffdd.opsconsole.common.boundary;

import java.util.List;
import java.util.Map;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;

/** Internal account projections. The C5 adapter authorizes the administrator and customer first. */
public interface PhoneReplacementAdminReadContract extends DomainFacade {
    record Scope(String sourceEnvironment,String runId) { }
    Scope scope();
    Account account(long userId);
    Map<String,Object> overview(Account account);
    Request request(Account account, String requestId);
    List<Map<String,Object>> requestHistory(Account account, int offset, int limit);
    List<Map<String,Object>> grants(Account account, int offset, int limit);
}
