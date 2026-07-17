package ffdd.opsconsole.emergency.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsKillSwitchAutoTriggerScheduler {
    private final OpsKillSwitchAutoTriggerService autoTriggerService;
    private final OpsKillSwitchService killSwitchService;

    @EventListener(ApplicationReadyEvent.class)
    public void repairOrphanedConfirmationsOnStartup() {
        killSwitchService.repairLegacyLastChangeLabels();
        killSwitchService.repairAutoConfirmationOrphans();
    }

    @Scheduled(
            initialDelayString = "${nexion.ops.emergency.kill-switch-auto-trigger-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.emergency.kill-switch-auto-trigger-delay-ms:60000}")
    public void evaluateAutoTriggers() {
        killSwitchService.repairAutoConfirmationOrphans();
        autoTriggerService.evaluateAndApply();
    }

    @Scheduled(
            initialDelayString = "${nexion.ops.emergency.kill-switch-auto-reminder-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.emergency.kill-switch-auto-reminder-delay-ms:60000}")
    public void remindOverdueConfirmations() {
        killSwitchService.remindOverdueAutoConfirmations();
    }
}
