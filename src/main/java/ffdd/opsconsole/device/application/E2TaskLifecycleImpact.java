package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.TaskConfigRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class E2TaskLifecycleImpact {
    static final long PRICING_FALLBACK_LIMIT = 100;
    private static final String NEW_TASK = "(new)";
    private static final Map<String, Integer> STATUS_ORDER = Map.of("active", 1, "paused", 2, "inactive", 3);
    private static final Set<String> DISPATCH_KILL_STATES = Set.of("kill", "killed", "已 kill", "已kill");

    record Terms(String id, String taskClass, BigDecimal min, BigDecimal max, int vram, boolean enabled,
                 BigDecimal rewardRate) {
        boolean widens(Terms before, boolean pricing) {
            return before == null ? enabled || max.signum() > 0
                    : OpsDeviceService.e2TaskPricingAmplifiesFinancialOutflow(
                            before.min, before.max, before.vram, before.enabled, min, max,
                            pricing || id.equals(before.id) ? vram : before.vram, enabled)
                        || rewardRate != null && rewardRate.compareTo(before.rewardRate) > 0;
        }

        String describe() {
            return min.toPlainString() + "–" + max.toPlainString() + "/" + vram + "GB"
                    + (rewardRate != null ? "/" + rewardRate.toPlainString() + "/s" : enabled ? "/启用" : "/停派");
        }
    }

    record Effect(Terms before, Terms after) {
        boolean widens(boolean pricing) {
            return after != null && after.widens(before, pricing);
        }

        String describe(boolean proposed) {
            Terms terms = proposed ? after : before;
            return terms == null ? "无" : terms.taskClass() + " " + terms.describe();
        }
    }

    record Impact(Map<String, Effect> pricing, Map<Integer, Effect> dispatch, Map<Integer, Effect> cloud, Effect rowPricing) {
        boolean widens() {
            return pricing.values().stream().anyMatch(effect -> effect.widens(true))
                    || dispatch.values().stream().anyMatch(effect -> effect.widens(false))
                    || cloud.values().stream().anyMatch(effect -> effect.widens(false))
                    || rowPricing != null && rowPricing.widens(true);
        }

        String describe(boolean proposed) {
            List<String> lines = new ArrayList<>();
            pricing.forEach((taskClass, effect) -> {
                if (!Objects.equals(effect.before(), effect.after()))
                    lines.add(taskClass + " 定价 " + effect.describe(proposed));
            });
            dispatch.forEach((vram, effect) -> {
                if (effect.widens(false)) lines.add("派发 " + vram + "GB " + effect.describe(proposed));
            });
            cloud.forEach((vram, effect) -> {
                if (effect.widens(false)) lines.add("Cloud 派发 " + vram + "GB " + effect.describe(proposed));
            });
            if (rowPricing != null && rowPricing.widens(true)) {
                lines.add("任务 " + rowPricing.describe(proposed));
            }
            return String.join("；", lines);
        }
    }

    static Impact compare(List<DeviceTaskView> catalog, DeviceTaskView before, DeviceTaskUpsertRequest request,
                          LocalDateTime effectiveAt, String cloudTaskConfig) {
        var affectedClasses = new LinkedHashSet<String>();
        if (before != null) affectedClasses.add(before.taskClass());
        var proposed = new ArrayList<>(catalog);
        DeviceTaskView next;
        if (request == null) {
            proposed.removeIf(task -> task.taskId().equals(before.taskId()));
            next = null;
        } else {
            affectedClasses.add(request.taskClass());
            next = new DeviceTaskView(before == null ? NEW_TASK : before.taskId(),
                    request.name(), request.price(), request.unit(), request.requirement(), request.saturation(),
                    request.status(), request.taskClass(), request.model(), request.minReward(), request.maxReward(),
                    request.minVram(), request.killInit(), before == null ? effectiveAt : before.createdAt(), effectiveAt);
            if (request.simulation() != null) {
                var settings = request.simulation();
                next = next.withSimulation(new SimulatedTaskPolicyService.TypeView(
                        settings.durationMinutes(), settings.baseReward(), settings.asset(), settings.enabled(),
                        settings.expectedVersion() + 1, effectiveAt));
            }
            if (before == null) {
                proposed.add(next);
            } else {
                DeviceTaskView replacement = next;
                proposed.replaceAll(task -> task.taskId().equals(before.taskId()) ? replacement : task);
            }
        }

        List<DeviceTaskView> currentRows = newestFirst(catalog);
        List<DeviceTaskView> proposedRows = newestFirst(proposed);
        Map<String, Effect> pricing = new LinkedHashMap<>();
        for (String taskClass : affectedClasses) {
            pricing.put(taskClass, new Effect(pricingTerms(pricingTask(currentRows, taskClass)),
                    pricingTerms(pricingTask(proposedRows, taskClass))));
        }
        List<TaskConfigRow> currentDispatch = dispatchRows(currentRows);
        List<TaskConfigRow> proposedDispatch = dispatchRows(proposedRows);
        Map<Integer, Effect> cloud = cloudTaskConfig.isEmpty() ? Map.of()
                : dispatchEffects(cloudRows(currentDispatch, cloudTaskConfig), cloudRows(proposedDispatch, cloudTaskConfig));
        // Existing E2 approval also guards edits of reward and VRAM on formal rows outside both projections.
        Effect rowPricing = before == null || before.simulation() != null || next == null || next.simulation() != null
                ? null : new Effect(pricingTerms(before, false), pricingTerms(next, false));
        return new Impact(pricing, dispatchEffects(currentDispatch, proposedDispatch), cloud, rowPricing);
    }

    private static Map<Integer, Effect> dispatchEffects(List<TaskConfigRow> current, List<TaskConfigRow> proposed) {
        Set<Integer> thresholds = new TreeSet<>(Set.of(0));
        for (List<TaskConfigRow> rows : List.of(current, proposed)) {
            rows.stream().map(TaskConfigRow::minVram).filter(Objects::nonNull).forEach(thresholds::add);
        }
        Map<Integer, Effect> dispatch = new LinkedHashMap<>();
        for (int vram : thresholds) {
            Effect effect = new Effect(dispatchTerms(current, vram), dispatchTerms(proposed, vram));
            if (!Objects.equals(effect.before(), effect.after())) dispatch.put(vram, effect);
        }
        return dispatch;
    }

    private static List<DeviceTaskView> newestFirst(List<DeviceTaskView> rows) {
        // The locked catalog comes back in primary-key order. Both consumers break timestamp ties by id DESC.
        List<DeviceTaskView> result = new ArrayList<>(rows);
        Collections.reverse(result);
        result.sort(Comparator.comparing(DeviceTaskView::updatedAt, Comparator.reverseOrder()));
        return result;
    }

    private static DeviceTaskView pricingTask(List<DeviceTaskView> rows, String taskClass) {
        String canonicalId = OpsDeviceService.E2_CANONICAL_TASK_ID.get(taskClass);
        return rows.stream().filter(task -> task.taskId().equalsIgnoreCase(canonicalId)
                        && OpsDeviceService.isFormalTaskOfClass(task, taskClass)).findFirst()
                .orElseGet(() -> rows.stream().filter(task -> taskClass.equalsIgnoreCase(task.taskClass()))
                        .sorted(Comparator.comparingInt(task -> STATUS_ORDER.getOrDefault(
                                task.status().toLowerCase(Locale.ROOT), 0)))
                        .limit(PRICING_FALLBACK_LIMIT)
                        .filter(task -> OpsDeviceService.isFormalTaskOfClass(task, taskClass)).findFirst().orElse(null));
    }

    private static Terms pricingTerms(DeviceTaskView task) {
        return task == null ? null : pricingTerms(task, OpsDeviceService.e2TaskEnabled(task));
    }

    private static Terms pricingTerms(DeviceTaskView task, boolean enabled) {
        return new Terms(task.taskId(), task.taskClass(),
                task.minReward().stripTrailingZeros(), task.maxReward().stripTrailingZeros(),
                OpsDeviceService.parseVram(task.minVram()), enabled, null);
    }

    private static List<TaskConfigRow> dispatchRows(List<DeviceTaskView> rows) {
        return rows.stream().filter(task -> task.simulation() == null
                        && "active".equalsIgnoreCase(task.status().trim())
                        && !DISPATCH_KILL_STATES.contains(task.killInit().trim().toLowerCase(Locale.ROOT)))
                .map(task -> {
                    int vram = OpsDeviceService.parseVram(task.minVram());
                    return new TaskConfigRow(task.taskId(), task.name(), task.taskClass(), task.model(),
                            task.minReward(), task.maxReward(), vram < 0 ? null : vram, task.status(), task.killInit());
                }).toList();
    }

    private static List<TaskConfigRow> cloudRows(List<TaskConfigRow> rows, String configuredTask) {
        return rows.stream().anyMatch(task -> task.minVram() == null) ? List.of()
                : AppTaskAssignmentService.testCloudTaskPool(rows, configuredTask);
    }

    private static Terms dispatchTerms(List<TaskConfigRow> rows, int vram) {
        if (rows.stream().anyMatch(task -> task.minVram() == null)) return null;
        return AppTaskAssignmentService.preferredTask(rows, vram)
                .map(task -> new Terms(task.taskId(), task.taskClass(), task.minReward().stripTrailingZeros(),
                        task.maxReward().stripTrailingZeros(), task.minVram(), true,
                        AppTaskAssignmentService.taskRewardRate(task).stripTrailingZeros()))
                .orElse(null);
    }
}
