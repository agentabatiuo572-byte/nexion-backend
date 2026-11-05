package ffdd.maintenance;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.application.AdminMfaCipher;
import ffdd.opsconsole.auth.application.AdminMfaProperties;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.AdminAccountGovernance;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.ApplicationNameProperties;
import ffdd.opsconsole.shared.audit.AuditLogSanitizer;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditProperties;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Local process entry point. Deliberately outside the backend's component scan; no HTTP or scheduler. */
public final class RestoreSuperAdmin {
    private RestoreSuperAdmin() {}

    public static void main(String[] args) {
        if (args.length != 3 || !args[0].matches("[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("Usage: RestoreSuperAdmin <account-id> <executor> <reason>");
        long targetId = Long.parseLong(args[0]);
        String executor = args[1].trim();
        String reason = args[2].trim();
        if (A2RuntimePolicy.visibleCodePointCount(executor) == 0 || executor.length() > 64
                || executor.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("EXECUTOR_REQUIRED_MAX_64");
        if (!StringUtils.hasText(System.getenv("NEXION_DB_URL"))
                || !StringUtils.hasText(System.getenv("NEXION_DB_USERNAME"))
                || System.getenv("NEXION_DB_PASSWORD") == null)
            throw new IllegalStateException("Explicit server NEXION_DB_URL/USERNAME/PASSWORD required");

        try (var context = new SpringApplicationBuilder(RecoveryConfiguration.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .run("--spring.datasource.hikari.minimum-idle=0", "--spring.datasource.hikari.maximum-pool-size=1")) {
            var admins = context.getBean(AdminMapper.class);
            var roles = context.getBean(AdminRoleRelationMapper.class);
            var states = context.getBean(AdminAccountStateMapper.class);
            var audit = context.getBean(AuditLogService.class);
            var policy = context.getBean(A2RuntimePolicy.class);
            var cipher = context.getBean(AdminMfaCipher.class);
            String recoveryId = UUID.randomUUID().toString();
            Integer effectiveAfter = new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .execute(transaction -> {
                        AdminEntity target = admins.selectActiveForUpdate(targetId);
                        if (target == null) throw new IllegalArgumentException("ACCOUNT_NOT_FOUND");
                        if (roles.lockActiveRoleIdByCode("SUPER_ADMIN") == null)
                            throw new IllegalStateException("SUPER_ADMIN_ROLE_UNAVAILABLE");
                        // Establish the RR read view only after the shared role lock, like A1's target/role lock order.
                        policy.validateReason(reason);
                        int effectiveBefore = 0;
                        for (AdminEntity admin : admins.selectList(new LambdaQueryWrapper<AdminEntity>()
                                .eq(AdminEntity::getIsDeleted, 0).orderByAsc(AdminEntity::getId))) {
                            String role = roles.activeRoleCode(admin.getId());
                            boolean superRole = "SUPER_ADMIN".equals(role)
                                    || role == null && Integer.valueOf(1).equals(admin.getSuperAdmin());
                            if (AdminAccountGovernance.effectiveSuper(superRole ? "super" : "other",
                                    Integer.valueOf(1).equals(admin.getStatus()) ? "enabled" : "disabled",
                                    AdminAccountGovernance.mfaBound(states.selectActiveByAdminId(admin.getId()))))
                                effectiveBefore++;
                        }
                        if (effectiveBefore >= AdminAccountGovernance.MIN_EFFECTIVE_SUPERS)
                            throw new IllegalStateException("RECOVERY_NOT_REQUIRED");
                        var state = states.selectActiveByAdminId(targetId);
                        if (!AdminAccountGovernance.mfaBound(state))
                            throw new IllegalStateException("TARGET_MFA_NOT_BOUND");
                        cipher.decrypt(state.getTfaSecretEncrypted());
                        String fromRole = roles.activeRoleCode(targetId);
                        boolean alreadySuper = "SUPER_ADMIN".equals(fromRole)
                                || fromRole == null && Integer.valueOf(1).equals(target.getSuperAdmin());
                        if (alreadySuper && Integer.valueOf(1).equals(target.getStatus()))
                            throw new IllegalStateException("TARGET_ALREADY_EFFECTIVE_SUPER");
                        if (admins.updateRoleIfVersion(targetId, target.getVersion(), 1) != 1
                                || admins.updateStatusIfVersion(targetId, target.getVersion() + 1, 1) != 1)
                            throw new IllegalStateException("ACCOUNT_VERSION_STALE");
                        // Like A1 promotion to super, existing service/supervisor qualifications stay compatible.
                        roles.disableOtherPrimaryRoles(targetId, "SUPER_ADMIN");
                        roles.ensurePrimaryRole(targetId, "SUPER_ADMIN");
                        if (!"SUPER_ADMIN".equals(roles.activeRoleCode(targetId)))
                            throw new IllegalStateException("SUPER_ADMIN_ROLE_WRITE_FAILED");
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("sourceDomain", "A");
                        detail.put("reason", reason);
                        detail.put("recoveryId", recoveryId);
                        detail.put("serverOsUser", System.getProperty("user.name"));
                        detail.put("targetUsername", target.getUsername());
                        detail.put("fromRoleCode", fromRole);
                        detail.put("toRoleCode", "SUPER_ADMIN");
                        detail.put("fromStatus", target.getStatus());
                        detail.put("toStatus", 1);
                        detail.put("effectiveSupersBefore", effectiveBefore);
                        detail.put("effectiveSupersAfter", effectiveBefore + 1);
                        audit.recordRequired(AuditLogWriteRequest.builder()
                                .action("A1_SUPER_ADMIN_BREAK_GLASS_RECOVERED").resourceType("A1_ADMIN_ACCOUNT")
                                .resourceId(String.valueOf(targetId)).actorType("SERVER_OPERATOR").actorUsername(executor)
                                .method("CLI").path("RestoreSuperAdmin").result("SUCCESS").riskLevel("HIGH")
                                .detail(detail).build());
                        return effectiveBefore + 1;
                    });
            System.out.printf("RECOVERED recoveryId=%s accountId=%d effectiveSupers=%d%n", recoveryId, targetId, effectiveAfter);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AuditProperties.class, ApplicationNameProperties.class, AdminMfaProperties.class})
    @MapperScan(basePackageClasses = {AdminMapper.class, AdminAccountStateMapper.class, AuditLogMapper.class})
    @Import({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            JacksonAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            MybatisPlatformConfigRepository.class, A2RuntimePolicy.class, AuditLogService.class,
            AuditLogSanitizer.class, AdminMfaCipher.class})
    static class RecoveryConfiguration {}
}
