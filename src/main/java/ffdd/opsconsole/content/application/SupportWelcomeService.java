package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.dto.SupportMessagePayload;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportReceptionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportWelcomeService {
    private final SupportReceptionMapper reception;
    private final java.time.Clock clock;
    private final SupportBindingMapper bindings;
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final SupportOwnershipService ownership;
    private final AuditLogService audit;
    private final AdminIdempotencyService commands;

    public record Texts(String zh,String en,String vi) {}
    public record Config(long version,Texts texts,String updatedAt,String updatedBy,String reason) {}
    public record Update(Long expectedVersion,Texts texts,String reason) {}

    public Config config() {
        var row=reception.welcome();
        if(row==null) throw new BizException(503,"SUPPORT_WELCOME_NOT_CONFIGURED");
        return new Config(row.version(),new Texts(row.zh(),row.en(),row.vi()),
                row.updatedAt()==null?null:row.updatedAt().toInstant(java.time.ZoneOffset.UTC).toString(),row.updatedBy(),row.reason());
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public ApiResult<Config> update(String key,Update request) {
        ownership.requireSuperAdminSnapshot();
        long actor=ownership.actorId();
        if(bindings.rulesWriteGrantSnapshot(actor).isEmpty()) throw new BizException(403,"SUPPORT_RULES_FORBIDDEN");
        if(request==null || request.expectedVersion()==null || request.expectedVersion()<1 || request.texts()==null)
            throw new BizException(422,"SUPPORT_WELCOME_INVALID");
        SupportBindingService.validateCommand(key,request.reason());
        var texts=new Texts(text(request.texts().zh()),text(request.texts().en()),text(request.texts().vi()));
        var data=new Update(request.expectedVersion(),texts,request.reason().strip());
        String hash=SupportHumanMessageService.sha(SupportMessagePayload.encode(data));
        return (ApiResult<Config>)commands.executeRetained("SUPPORT_WELCOME:"+actor,key,hash,ApiResult.class,()->{
            ownership.lockAgent(actor);
            ownership.requireSuperAdmin();
            if(bindings.rulesWriteGrant(actor).isEmpty()) throw new BizException(403,"SUPPORT_RULES_FORBIDDEN");
            if(reception.saveWelcome(data.expectedVersion(),texts.zh(),texts.en(),texts.vi(),
                    AdminActorResolver.resolve(null),data.reason())!=1)
                throw new BizException(409,"SUPPORT_WELCOME_VERSION_CONFLICT");
            Config result=config();
            audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_WELCOME_CHANGED").resourceType("SUPPORT_WELCOME")
                    .resourceId("1").actorType("ADMIN").actorUsername(AdminActorResolver.resolve(null)).result("SUCCESS")
                    .detail(Map.of("key",key,"version",result.version(),"reason",data.reason())).build());
            return ApiResult.ok(result);
        });
    }

    private String text(String value) {
        String clean=value==null?"":value.strip();
        if(clean.isEmpty() || clean.codePointCount(0,clean.length())>500) throw new BizException(422,"SUPPORT_WELCOME_TEXT_INVALID");
        return clean;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void register(long customerId,String language) {
        ownership.lockCustomer(customerId);
        // Canonical customer support is isolated from the acceptance-sandbox rails.
        if(bindings.canonicalCustomer(customerId)!=1) return;
        String no="CV-WEL-"+customerId;
        if(reception.existingWelcome(no)!=null) return;
        var binding=bindings.current(customerId);
        Long actor=binding==null ? null : binding.agentAdminId();
        String name=actor==null ? null : messages.senderDisplayName(actor);
        Texts texts=config().texts();
        String body="zh".equals(language)?texts.zh():"vi".equals(language)?texts.vi():texts.en();
        if(conversations.insertTimeoutSnapshot(no)!=1) throw new BizException(503,"M3_TIMEOUT_POLICY_NOT_CONFIGURED");
        LocalDateTime now=LocalDateTime.now(clock);
        reception.welcomeConversation(no,customerId,actor,name,body,now);
        reception.welcomeMessage(no,actor,name,body,now);
    }
}
