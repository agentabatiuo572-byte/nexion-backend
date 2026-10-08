package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper.Row;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportInvitationReadService implements SupportInvitationReadFacade {
    private final SupportInvitationReadMapper mapper;

    /** An enclosing statistics transaction must also use RR; all reads join that same snapshot. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Invitation> readInvitations(Collection<Long> rootCustomerIds) {
        if (rootCustomerIds == null || rootCustomerIds.isEmpty()
                || rootCustomerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("INVALID_INVITATION_ROOT_SCOPE");
        var roots = new TreeSet<>(rootCustomerIds);
        var nodes = new TreeMap<Long, Row>();
        var edges = new HashMap<Long, Set<Long>>();
        var problems = new HashMap<Long, Set<Reason>>();
        var batchProblems = EnumSet.noneOf(Reason.class);
        try {
            addUsers(mapper.readUsers(List.copyOf(roots)), roots, nodes, problems, batchProblems);
        } catch (DataAccessException ex) {
            return roots.stream().map(root -> new Invitation(root, null, List.of(), List.of(), Completeness.FAILED,
                Set.of(Reason.SOURCE_READ_FAILED))).toList();
        }
        var frontier = new TreeSet<Long>();
        roots.stream().filter(nodes::containsKey).forEach(frontier::add);
        var expanded = new HashSet<Long>();
        while (!frontier.isEmpty()) {
            List<Row> rows;
            try { rows = mapper.children(List.copyOf(frontier)); }
            catch (DataAccessException ex) { batchProblems.add(Reason.SOURCE_READ_FAILED); break; }
            if (rows == null) { batchProblems.add(Reason.INVALID_ROW); break; }
            // Validate the entire returned scope before interpreting any malformed local row.
            for (var row : rows)
                if (row != null && row.sponsorCustomerId() != null && !frontier.contains(row.sponsorCustomerId()))
                    throw new IllegalStateException("INVALID_INVITATION_FRONTIER_SCOPE");
            expanded.addAll(frontier);
            var next = new TreeSet<Long>();
            for (var row : rows) {
                if (row == null || row.customerId() == null || row.customerId() <= 0 || row.sponsorCustomerId() == null) {
                    batchProblems.add(Reason.INVALID_ROW); continue;
                }
                var children = edges.computeIfAbsent(row.sponsorCustomerId(), ignored -> new TreeSet<>());
                if (!children.add(row.customerId())) problem(problems, row.sponsorCustomerId(), Reason.DUPLICATE_EDGE);
                merge(row, nodes, problems);
                if (!expanded.contains(row.customerId())) next.add(row.customerId());
            }
            frontier = next;
        }
        // Only referenced parents of observed nodes are checked; this is not a global orphan certificate.
        var parents = new TreeSet<Long>();
        for (var node : nodes.values())
            if (node.sponsorCustomerId() != null && node.sponsorCustomerId() > 0 && !nodes.containsKey(node.sponsorCustomerId()))
                parents.add(node.sponsorCustomerId());
        if (!parents.isEmpty()) {
            var existingParents = new TreeMap<Long, Row>();
            try {
                var parentRows = mapper.readUsers(List.copyOf(parents));
                addUsers(parentRows, parents, existingParents, problems, batchProblems);
                boolean validRows = parentRows != null && parentRows.stream().noneMatch(row -> row == null || row.customerId() == null);
                for (var node : nodes.values()) {
                    if (node.sponsorCustomerId() == null || !parents.contains(node.sponsorCustomerId())) continue;
                    Row parent = existingParents.get(node.sponsorCustomerId());
                    if (parent == null) {
                        if (validRows) problem(problems, node.customerId(), Reason.DANGLING_SPONSOR);
                    } else if (!environmentKnown(parent)) problem(problems, node.customerId(), Reason.UNKNOWN_ENVIRONMENT);
                    else if (environmentKnown(node) && !parent.sandbox().equals(node.sandbox()))
                        problem(problems, node.customerId(), Reason.ENVIRONMENT_CONFLICT);
                }
            } catch (DataAccessException ex) { batchProblems.add(Reason.SOURCE_READ_FAILED); }
        }
        return roots.stream().map(root -> invitation(root, nodes, edges, problems, batchProblems)).toList();
    }

    private static void addUsers(List<Row> rows, Set<Long> scope, Map<Long, Row> nodes,
                                 Map<Long, Set<Reason>> problems, Set<Reason> batchProblems) {
        if (rows == null) { batchProblems.add(Reason.INVALID_ROW); return; }
        for (var row : rows)
            if (row != null && row.customerId() != null && !scope.contains(row.customerId()))
                throw new IllegalStateException("INVALID_INVITATION_ROOT_SCOPE");
        for (var row : rows) {
            if (row == null || row.customerId() == null) { batchProblems.add(Reason.INVALID_ROW); continue; }
            if (nodes.containsKey(row.customerId())) problem(problems, row.customerId(), Reason.DUPLICATE_EDGE);
            merge(row, nodes, problems);
        }
    }

    private static void merge(Row row, Map<Long, Row> nodes, Map<Long, Set<Reason>> problems) {
        Row previous = nodes.putIfAbsent(row.customerId(), row);
        if (previous != null && !previous.equals(row)) {
            problem(problems, row.customerId(), Reason.CONFLICTING_EDGE);
            if (previous.sponsorCustomerId() != null) problem(problems, previous.sponsorCustomerId(), Reason.CONFLICTING_EDGE);
            if (row.sponsorCustomerId() != null) problem(problems, row.sponsorCustomerId(), Reason.CONFLICTING_EDGE);
        }
        if (row.deleted() == null || row.deleted() < 0 || row.deleted() > 1
                || row.status() == null || row.status().isBlank()
                || row.sponsorCustomerId() != null && row.sponsorCustomerId() < 0)
            problem(problems, row.customerId(), Reason.INVALID_ROW);
        if (Integer.valueOf(1).equals(row.deleted())) problem(problems, row.customerId(), Reason.DELETED_NODE);
    }

    private static Invitation invitation(long root, Map<Long, Row> nodes, Map<Long, Set<Long>> edges,
                                         Map<Long, Set<Reason>> problems, Set<Reason> batchProblems) {
        var reasons = EnumSet.noneOf(Reason.class);
        reasons.addAll(batchProblems);
        Row owner = nodes.get(root);
        if (owner == null) {
            reasons.add(Reason.MISSING_ROOT);
            return new Invitation(root, null, List.of(), List.of(), Completeness.UNKNOWN, reasons);
        }
        boolean knownEnvironment = environmentKnown(owner);
        if (!knownEnvironment) reasons.add(Reason.UNKNOWN_ENVIRONMENT);
        var direct = new TreeSet<Long>();
        var descendants = new TreeSet<Long>();
        var visited = new HashSet<Long>();
        var predecessors = new HashMap<Long, Long>();
        var pending = new ArrayDeque<Path>();
        pending.add(new Path(root, knownEnvironment));
        visited.add(root);
        while (!pending.isEmpty()) {
            Path path = pending.removeFirst();
            reasons.addAll(problems.getOrDefault(path.customerId(), Set.of()));
            for (long child : edges.getOrDefault(path.customerId(), Set.of())) {
                if (!visited.add(child)) {
                    boolean cycle = false;
                    for (Long ancestor = path.customerId(); ancestor != null; ancestor = predecessors.get(ancestor))
                        if (ancestor == child) { cycle = true; break; }
                    reasons.add(cycle ? Reason.CYCLE : Reason.CONFLICTING_EDGE);
                    continue;
                }
                predecessors.put(child, path.customerId());
                Row node = nodes.get(child);
                if (node == null) { reasons.add(Reason.INVALID_ROW); continue; }
                boolean sameEnvironment = environmentKnown(node) && knownEnvironment && node.sandbox().equals(owner.sandbox());
                if (!environmentKnown(node)) reasons.add(Reason.UNKNOWN_ENVIRONMENT);
                else if (knownEnvironment && !node.sandbox().equals(owner.sandbox())) reasons.add(Reason.ENVIRONMENT_CONFLICT);
                boolean eligible = path.eligible() && sameEnvironment;
                if (eligible && Integer.valueOf(0).equals(node.deleted())) {
                    descendants.add(child);
                    if (path.customerId() == root) direct.add(child);
                }
                // Even deleted, disabled or conflicting environment nodes are expanded; contaminated paths stay excluded.
                pending.addLast(new Path(child, eligible));
            }
        }
        Completeness completeness = reasons.isEmpty() ? Completeness.COMPLETE : !knownEnvironment ? Completeness.UNKNOWN
            : reasons.contains(Reason.SOURCE_READ_FAILED) && descendants.isEmpty() ? Completeness.FAILED : Completeness.PARTIAL;
        return new Invitation(root, owner.sandbox(), List.copyOf(direct), List.copyOf(descendants), completeness, reasons);
    }

    private static boolean environmentKnown(Row row) {
        return Integer.valueOf(0).equals(row.sandbox()) || Integer.valueOf(1).equals(row.sandbox());
    }

    private static void problem(Map<Long, Set<Reason>> problems, long customer, Reason reason) {
        problems.computeIfAbsent(customer, ignored -> EnumSet.noneOf(Reason.class)).add(reason);
    }

    private record Path(long customerId, boolean eligible) { }
}
