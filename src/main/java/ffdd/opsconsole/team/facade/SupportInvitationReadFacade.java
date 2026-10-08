package ffdd.opsconsole.team.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Internal aggregate input. The caller authorizes roots and must not expose out-of-scope descendants. */
public interface SupportInvitationReadFacade extends DomainFacade {
    List<Invitation> readInvitations(Collection<Long> rootCustomerIds);

    /** COMPLETE proves traversal of the current relationship snapshot, not financial history coverage. */
    enum Completeness { COMPLETE, PARTIAL, UNKNOWN, FAILED }

    enum Reason {
        MISSING_ROOT, UNKNOWN_ENVIRONMENT, ENVIRONMENT_CONFLICT, DELETED_NODE,
        DANGLING_SPONSOR, INVALID_ROW, DUPLICATE_EDGE, CONFLICTING_EDGE, CYCLE, SOURCE_READ_FAILED
    }

    /**
     * Observed, same-environment, retained customer IDs stay inside the server. Deleted intermediates are
     * traversed but excluded, and PARTIAL never promises a complete count. Overlapping roots cannot be added.
     */
    record Invitation(long rootCustomerId, Integer rootSandbox, List<Long> directCustomerIds, List<Long> descendantCustomerIds,
                      Completeness completeness, Set<Reason> reasons) {
        public Invitation {
            directCustomerIds = List.copyOf(directCustomerIds);
            descendantCustomerIds = List.copyOf(descendantCustomerIds);
            reasons = Set.copyOf(reasons);
        }
    }
}
