package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportHumanMessageMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.*;

/** Metadata and durable client-message deduplication share the message transaction and customer lock. */
@ApplicationService
@RequiredArgsConstructor
public class SupportHumanMessageService {
    private final SupportHumanMessageMapper messages;
    private final SupportBindingMapper bindings;
    private final SupportOwnershipService ownership;
    private final SupportMaintenanceService maintenance;
    private final SupportAttachmentService attachments;

    public static boolean validContent(String kind,String body) {
        return Set.of("IMAGE","SKU","LINK").contains(kind==null?"TEXT":kind) ? body==null || body.trim().length()<=2000
            : (kind==null || "TEXT".equals(kind)) && body!=null && !body.trim().isEmpty() && body.trim().length()<=2000;
    }
    public static String text(String body) { return body==null ? "" : body.trim(); }

    @Transactional(propagation=Propagation.MANDATORY)
    public Prepared prepare(Long customer,String actorType,Long actor,String key,String operation,Object payload,
            String client,String kind,String intent,String attachment,Long expectedAssignment) {
        ownership.lockCustomer(customer);
        if(messages.captureFence()==null) throw new BizException(503,"SUPPORT_MESSAGE_CAPTURE_UNAVAILABLE");
        SupportAssignment assignment="ADMIN".equals(actorType) ? ownership.requireWriter(customer,true) : bindings.current(customer);
        String normalizedKind=kind==null?"TEXT":kind, normalizedIntent=intent==null?"SERVICE":intent;
        String sku=payload instanceof ffdd.opsconsole.content.dto.ConversationReplyRequest r?r.skuId():payload instanceof ffdd.opsconsole.content.dto.ConversationInitiateRequest r?r.skuId():null;
        var link=payload instanceof ffdd.opsconsole.content.dto.ConversationReplyRequest r?r.linkTarget():payload instanceof ffdd.opsconsole.content.dto.ConversationInitiateRequest r?r.linkTarget():null;
        if(!Set.of("TEXT","IMAGE","SKU","LINK").contains(normalizedKind) || !Set.of("SERVICE","MAINTENANCE").contains(normalizedIntent)
            || (!"ADMIN".equals(actorType) && !"SERVICE".equals(normalizedIntent))
            || (!"ADMIN".equals(actorType) && Set.of("SKU","LINK").contains(normalizedKind))
            || ("SKU".equals(normalizedKind) != (sku!=null && !sku.isBlank()))
            || ("LINK".equals(normalizedKind) != (link!=null))
            || ("IMAGE".equals(normalizedKind) != (attachment!=null && !attachment.isBlank())))
            throw new BizException(422,"SUPPORT_MESSAGE_INPUT_INVALID");
        boolean modern=client!=null || !"TEXT".equals(normalizedKind) || "MAINTENANCE".equals(normalizedIntent);
        if(modern && (client==null || !client.matches("[A-Za-z0-9_-]{8,128}")))
            throw new BizException(422,"SUPPORT_CLIENT_MESSAGE_ID_REQUIRED");
        if(expectedAssignment!=null && (expectedAssignment<1 || expectedAssignment>9007199254740991L))
            throw new BizException(422,"SUPPORT_ASSIGNMENT_INVALID");
        if(("ADMIN".equals(actorType) && modern && expectedAssignment==null)
            || expectedAssignment!=null && (assignment==null || !expectedAssignment.equals(assignment.id())))
            throw new BizException(409,"SUPPORT_ASSIGNMENT_CHANGED");
        String clientId=modern?client:"legacy_"+sha(operation+":"+key);
        String digest=sha(ffdd.opsconsole.content.dto.SupportMessagePayload.encode(java.util.Arrays.asList(customer,operation,payload)));
        Map<String,Object> old=messages.find(actorType,actor,clientId);
        if(old!=null && (!Objects.equals(digest,old.get("payloadHash")) || !customer.equals(((Number)old.get("customerId")).longValue())))
            throw new BizException(409,"SUPPORT_CLIENT_MESSAGE_CONFLICT");
        String skuName=null,linkJson=null;
        if(old==null && "SKU".equals(normalizedKind)) {
            if(!sku.matches("[A-Za-z0-9_-]{1,64}")) throw new BizException(422,"SUPPORT_SKU_INVALID");
            var value=messages.lockSku(sku);
            if(value==null || !"on".equalsIgnoreCase(String.valueOf(value.get("status")))
                || !(Boolean.TRUE.equals(value.get("storeVisible")) || value.get("storeVisible") instanceof Number visible && visible.intValue()==1)
                || !(value.get("price") instanceof java.math.BigDecimal price) || price.signum()<=0
                || value.get("publishBlocked") instanceof Number blocked && blocked.intValue()!=0 || Boolean.TRUE.equals(value.get("publishBlocked")))
                throw new BizException(409,"SUPPORT_SKU_UNAVAILABLE");
            skuName=value.get("name")==null?null:value.get("name").toString();
            if(skuName==null || skuName.isBlank()) throw new BizException(409,"SUPPORT_SKU_UNAVAILABLE");
        }
        if(old==null && link!=null) {
            if(!Set.of("HOME","WALLET","SUPPORT").contains(link.type()==null?"":link.type()) || link.params()!=null && !link.params().isEmpty())
                throw new BizException(422,"SUPPORT_LINK_INVALID");
            linkJson=ffdd.opsconsole.content.dto.SupportMessagePayload.encode(new ffdd.opsconsole.content.dto.SupportLinkTarget(link.type(),Map.of()));
        }
        return new Prepared(customer,actorType,actor,clientId,normalizedKind,normalizedIntent,attachment,assignment,digest,sku,skuName,linkJson,
            old==null?null:((Number)old.get("messageId")).longValue(),old==null?null:String.valueOf(old.get("conversationNo")));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void committed(Prepared p,Long messageId,String commandKey) {
        if(messageId==null) throw new IllegalStateException("Durable message id is required");
        if(p.attachment()!=null) attachments.attachToMessage(p.customer(),p.actorType(),p.actor(),p.assignment()==null?null:p.assignment().id(),p.attachment(),messageId);
        messages.insertMetadata(messageId,p.customer(),p.assignment()==null?null:p.assignment().id(),p.actorType(),p.actor(),
            p.client(),p.kind(),p.intent(),p.attachment(),p.hash(),p.skuId(),p.skuName(),p.linkTargetJson());
        if("MAINTENANCE".equals(p.intent())) maintenance.executed(p.customer(),p.assignment(),messageId,commandKey);
    }
    public Long latest(String no) { return messages.latest(no); }
    public record Prepared(Long customer,String actorType,Long actor,String client,String kind,String intent,String attachment,
            SupportAssignment assignment,String hash,String skuId,String skuName,String linkTargetJson,Long previousMessageId,String previousConversationNo) {
        public String content(String body) {
            if("SKU".equals(kind)) return text(body).isBlank()?skuName:text(body);
            if("LINK".equals(kind) && text(body).isBlank()) return "打开链接";
            return text(body);
        }
    }
    private static String sha(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
