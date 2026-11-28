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
        DANGLING_SPONSOR, INVALID_ROW, DUPLICATE_EDGE, CONFLICTING_EDGE, CYCLE, SOURCE_READ_FAILED,
        GENERATION_UPGRADING
    }

    /**
     * Observed, same-environment, retained customer IDs stay inside the server. Deleted intermediates are
     * traversed but excluded, and PARTIAL never promises a complete count. Overlapping roots cannot be added.
     * {@code withinRewardRange} counts depths 1-7; {@code beyondRewardRange} counts depths 8 and later.
     * Inviter-only problems are kept apart from the downward {@code reasons} and never affect counts.
     */
    record Invitation(long rootCustomerId, Integer rootSandbox, List<Long> directCustomerIds, List<Long> descendantCustomerIds,
                      int withinRewardRange, int beyondRewardRange, Completeness completeness, Set<Reason> reasons,
                      Set<Reason> inviterReasons) {
        public Invitation {
            directCustomerIds = List.copyOf(directCustomerIds);
            descendantCustomerIds = List.copyOf(descendantCustomerIds);
            reasons = Set.copyOf(reasons);
            inviterReasons = Set.copyOf(inviterReasons);
        }

        /** Source-compatible shape for callers that only provide the old depth-free result. */
        public Invitation(long rootCustomerId, Integer rootSandbox, List<Long> directCustomerIds,
                          List<Long> descendantCustomerIds, Completeness completeness, Set<Reason> reasons) {
            this(rootCustomerId, rootSandbox, directCustomerIds, descendantCustomerIds,
                    descendantCustomerIds.size(), 0, completeness, reasons, Set.of());
        }

        public int direct() { return directCustomerIds.size(); }
        public int total() { return descendantCustomerIds.size(); }
    }
}
