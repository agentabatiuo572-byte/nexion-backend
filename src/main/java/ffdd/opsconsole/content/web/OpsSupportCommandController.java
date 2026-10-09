package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class OpsSupportCommandController {
    private final AdminIdempotencyRecordMapper records;
    private final SupportOwnershipService ownership;
    private final ObjectMapper json;
    private final ffdd.opsconsole.content.domain.SupportTicketRepository tickets;
    private final ffdd.opsconsole.content.application.SupportBindingRandomService random;
    private final ffdd.opsconsole.content.application.SupportBulkService bulk;
    private final ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper timeoutPolicy;
    private static final List<String> SCOPES=List.of("SUPPORT_TRANSFER","SUPPORT_LEGACY_TRANSFER","SUPPORT_LEGACY_SINGLE","SUPPORT_RULES","SUPPORT_RANDOM",
            "M3_MAINTENANCE","M3_CONVERSATION_INITIATE","M3_CONVERSATION_REPLY","M3_CONVERSATION_STATUS","M3_CONVERSATION_ARCHIVE",
            "M3_CONVERSATION_ARCHIVE_BATCH","M3_CONVERSATION_TO_TICKET","M3_CUSTOMER_TAG_ADD","M3_CUSTOMER_TAG_REMOVE",
            "M3_CUSTOMER_NOTE_ADD","M3_CUSTOMER_NOTE_REMOVE","M2_SUPPORT_TICKET_CREATE","M2_SUPPORT_TICKET_REPLY","M2_SUPPORT_TICKET_ESCALATE",
            "M3_SUPPORT_BULK_CREATE","M3_SUPPORT_BULK_CANCEL","M3_SUPPORT_BULK_RETRY",
            "SUPPORT_GROUP_CREATE","SUPPORT_GROUP_RENAME","SUPPORT_GROUP_STATUS","SUPPORT_GROUP_OWNER",
            "SUPPORT_GROUP_MEMBER","SUPPORT_GROUP_QUALIFICATION","SUPPORT_GROUP_ROUTE");

    @GetMapping("/api/admin/content/support-workbench/commands/{key}")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m2_read','service_m3_read','platform_a1_read')")
    @Transactional
    public ApiResult<Map<String,Object>> recover(@PathVariable String key) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(key==null || key.isBlank() || key.length()>128) throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
        long actor=ownership.actorId();
        boolean supportRead=List.of("service_m1_read","service_m2_read","service_m3_read").stream().anyMatch(SupportOwnershipService::hasAuthority);
        List<String> scopes=new ArrayList<>(SCOPES.stream().filter(s->supportRead || s.equals("SUPPORT_GROUP_QUALIFICATION"))
                .map(s->s+":"+actor).toList());
        // Legacy timeout keys are global; only their existing configuration writers may recover them.
        if(supportRead && ownership.currentSuperAdmin() && !timeoutPolicy.timeoutManageGrant(actor).isEmpty())
            scopes.add("M3_CONVERSATION_TIMEOUT_POLICY");
        var found=records.selectSupportCommand(scopes,key.trim());
        if(found.isEmpty()) {
            if(!supportRead || key.trim().length()<8) throw new BizException(404,"SUPPORT_COMMAND_NOT_FOUND");
            var restored=bulk.recover(key);
            if("SUCCEEDED".equals(restored.get("status"))) return ApiResult.ok(restored);
            throw new BizException(404,"SUPPORT_COMMAND_NOT_FOUND");
        }
        if(found.size()!=1) throw new BizException(409,"SUPPORT_COMMAND_AMBIGUOUS");
        var receipt=found.get(0);
        if(!scopes.contains(receipt.getScope())) throw new BizException(404,"SUPPORT_COMMAND_NOT_FOUND");
        if(receipt.getScope().startsWith("SUPPORT_GROUP_")) {
            if(key.length()>96)throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
            if(receipt.getScope().equals("SUPPORT_GROUP_QUALIFICATION:"+actor)) {
                if(!SupportOwnershipService.hasAuthority("platform_a1_read"))throw new BizException(403,"SUPPORT_COMMAND_READ_FORBIDDEN");
                ownership.requireSuperAdmin();
            } else {
                if(!SupportOwnershipService.hasAuthority("service_m1_read"))throw new BizException(403,"SUPPORT_COMMAND_READ_FORBIDDEN");
                if(ownership.defaultQueryScope(null,null).mode()==ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.PERSONAL)
                    throw new BizException(403,"SUPPORT_COMMAND_READ_FORBIDDEN");
                if(receipt.getScope().equals("SUPPORT_GROUP_OWNER:"+actor)||receipt.getScope().equals("SUPPORT_GROUP_ROUTE:"+actor))
                    ownership.requireSuperAdmin();
            }
            // The original body may name objects now outside scope; fresh authorized GETs recover current facts.
            return ApiResult.ok(Map.of("status",receipt.getStatus()));
        }
        if(key.trim().length()<8)throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
        if("M3_CONVERSATION_TIMEOUT_POLICY".equals(receipt.getScope())) {
            if(!ownership.currentSuperAdmin() || timeoutPolicy.timeoutManageGrant(actor).isEmpty())
                throw new BizException(403,"M3_TIMEOUT_POLICY_FORBIDDEN");
            return ApiResult.ok(Map.of("status",receipt.getStatus()));
        }
        if(receipt.getScope().startsWith("M3_SUPPORT_BULK_CREATE:")) return ApiResult.ok(bulk.recover(key));
        if(receipt.getScope().startsWith("M3_SUPPORT_BULK_CANCEL:") || receipt.getScope().startsWith("M3_SUPPORT_BULK_RETRY:")) {
            if(!"SUCCEEDED".equals(receipt.getStatus())) return ApiResult.ok(Map.of("status",receipt.getStatus()));
            JsonNode result=json.readTree(receipt.getResponseJson()).path("data");
            if(!result.path("batchId").isTextual()) throw new BizException(409,"SUPPORT_COMMAND_RESULT_INVALID");
            return ApiResult.ok(Map.of("status","SUCCEEDED","resultType",receipt.getScope().split(":")[0],
                    "result",bulk.detail(result.path("batchId").asText())));
        }
        String permission=receipt.getScope().startsWith("M3_")?"service_m3_read":receipt.getScope().startsWith("M2_")?"service_m2_read":"service_m1_read";
        if(!SupportOwnershipService.hasAuthority(permission)) throw new BizException(403,"SUPPORT_COMMAND_READ_FORBIDDEN");
        if(receipt.getScope().startsWith("SUPPORT_RANDOM:")) return ApiResult.ok(random.recover(key));
        if(!"SUCCEEDED".equals(receipt.getStatus())) return ApiResult.ok(Map.of("status",receipt.getStatus()));
        JsonNode response=json.readTree(receipt.getResponseJson());
        Set<Long> customers=new TreeSet<>();
        collect(response.path("data"),customers);
        customers.forEach(ownership::lockCustomer);
        if(receipt.getScope().startsWith("SUPPORT_")) customers.forEach(ownership::requireManagingCustomer);
        else customers.forEach(ownership::requireRead);
        if(receipt.getScope().startsWith("SUPPORT_")) ownership.requireSupervisor();
        if(receipt.getScope().startsWith("M2_") && restrictedTicket(response.path("data")))
            return ApiResult.ok(Map.of("status","SUCCEEDED","contentRestricted",true));
        // Never project opaque successful content whose customer cannot be derived from the stored result.
        if(customers.isEmpty() && !receipt.getScope().startsWith("SUPPORT_RULES:"))
            return ApiResult.ok(Map.of("status","SUCCEEDED"));
        return ApiResult.ok(Map.of("status","SUCCEEDED","resultType",receipt.getScope().split(":")[0],"result",response));
    }

    private boolean restrictedTicket(JsonNode node) {
        if(node.isObject() && node.path("ticketNo").isTextual()
                && tickets.findByTicketNo(node.path("ticketNo").asText()).map(t->t.contentRestricted()).orElse(true)) return true;
        for(var child:node) if(restrictedTicket(child)) return true;
        return false;
    }

    private void collect(JsonNode node,Set<Long> customers) {
        if(node.isArray()){node.forEach(n->collect(n,customers));return;}
        if(!node.isObject())return;
        for(String field:List.of("customerId","userId")) if(node.path(field).canConvertToLong() && node.path(field).asLong()>0) customers.add(node.path(field).asLong());
        if(node.path("conversationNo").isTextual()) customers.add(ownership.conversationCustomer(node.path("conversationNo").asText()));
        if(node.path("ticketNo").isTextual()) customers.add(ownership.ticketCustomer(node.path("ticketNo").asText()));
        node.elements().forEachRemaining(n->collect(n,customers));
    }
}
