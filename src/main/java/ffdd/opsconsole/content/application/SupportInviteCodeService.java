package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.SupportGroupManagementViews.InviteCodeSummary;
import ffdd.opsconsole.content.dto.SupportGroupManagementViews.InviteCodeView;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class SupportInviteCodeService {
    public static final String SELF = "SUPPORT_INVITE_SELF";
    public static final String REVOKE = "SUPPORT_INVITE_REVOKE";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final SupportOwnershipService ownership;
    private final ProductionSupportPathGuard production;
    private final SupportBindingMapper bindings;
    private final SupportGroupMapper groups;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService commands;
    private final AuditLogService audit;

    @Transactional
    public InviteCodeView self() {
        long actor = ownership.actorId();
        requirePersonal(actor);
        return view(actor);
    }

    @Transactional
    public InviteCodeView managed(long admin) {
        requireManaged(admin);
        return view(admin);
    }

    @Transactional
    public void authorizeRecovery(boolean self, String key) {
        if (self) requirePersonal(ownership.actorId());
        else {
            Long admin = groups.revokedOwner(ownership.actorId(),key);
            if (admin == null) ownership.requireSupervisor();
            else requireManaged(admin);
        }
    }

    public InviteCodeView change(Long target, Long expected, String reason, String key) {
        production.requireOpsWriteAllowed();
        if (key==null || key.trim().length()<8 || key.trim().length()>96)
            throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
        long actor=ownership.actorId();
        boolean revoke=target!=null;
        long admin=revoke?target:actor;
        String detail;
        if (revoke) {
            detail = reason == null ? "" : reason.trim();
            int reasonLength = detail.codePointCount(0, detail.length());
            if (expected == null || reasonLength < 8 || reasonLength > 200) {
                throw new BizException(422, "SUPPORT_INVITE_REVOKE_INVALID");
            }
            requireManaged(admin);
        } else {
            requirePersonal(actor);
            detail = expected == null ? "Self issue" : "Self reset";
        }
        String hash=SupportHumanMessageService.sha(admin+"|"+expected+"|"+detail);
        return commands.execute((revoke?REVOKE:SELF)+":"+actor,key,hash,InviteCodeView.class,()->{
            for(long id:new TreeSet<>(List.of(actor,admin)))
                if(groups.lockAccount(id)==null) throw new BizException(404,"SUPPORT_ACCOUNT_NOT_FOUND");
            if(revoke) requireManaged(admin); else requirePersonal(actor);
            InviteCodeSummary before=groups.inviteCode(admin);
            if(!Objects.equals(expected,before==null?null:before.version()))
                throw new BizException(409,"SUPPORT_INVITE_VERSION_CONFLICT");
            if(before!=null && groups.revokeCode(admin,actor,detail,key.trim(),revoke?"REVOKE":"RESET")!=1)
                throw new BizException(409,"SUPPORT_INVITE_VERSION_CONFLICT");
            if(!revoke) {
                long version=groups.nextInviteVersion(admin);
                while(true) {
                    StringBuilder code=new StringBuilder("CS");
                    for(int i=0;i<6;i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                    try { groups.issueCode(admin,code.toString(),version,detail); break; }
                    catch(DuplicateKeyException collision) {
                        if(groups.codeExists(code.toString())!=1) throw collision;
                    }
                }
            }
            audit.recordRequired(AuditLogWriteRequest.builder().action(revoke?"SUPPORT_INVITE_REVOKED":before==null?"SUPPORT_INVITE_ISSUED":"SUPPORT_INVITE_RESET")
                    .resourceType("SUPPORT_INVITE_CODE").resourceId(String.valueOf(admin)).actorId(actor).result("SUCCESS")
                    .detail(Map.of("operationId",key.trim(),"reason",detail)).build());
            return view(admin);
        });
    }

    private InviteCodeView view(long admin) {
        InviteCodeSummary row=groups.inviteCode(admin);
        if(row==null) return new InviteCodeView(null,null,null,null);
        String link=config.activeValue(PlatformExperienceConfigService.BASE_URL_KEY)
                .filter(s->!s.isBlank()).map(s->s.trim()+row.code()).orElse(null);
        return new InviteCodeView(row.code(),link,row.version(),row.issuedAt());
    }
    private void requirePersonal(long admin) {
        if(bindings.eligibleAgent(admin)!=1) throw new BizException(403,"SUPPORT_PERSONAL_SERVICE_REQUIRED");
    }
    private void requireManaged(long admin) {
        var scope=ownership.defaultQueryScope(null,null);
        if(scope.mode()==ReadMode.PERSONAL || groups.managementAccount(scope,admin)==null)
            throw new BizException(403,"SUPPORT_INVITE_MANAGEMENT_FORBIDDEN");
    }
}
