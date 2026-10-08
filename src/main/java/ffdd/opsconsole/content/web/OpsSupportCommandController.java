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
    private static final List<String> SCOPES=List.of("SUPPORT_TRANSFER","SUPPORT_LEGACY_TRANSFER","SUPPORT_LEGACY_SINGLE","SUPPORT_RULES","SUPPORT_RANDOM",
            "M3_MAINTENANCE","M3_CONVERSATION_INITIATE","M3_CONVERSATION_REPLY","M3_CONVERSATION_STATUS","M3_CONVERSATION_ARCHIVE",
            "M3_CONVERSATION_ARCHIVE_BATCH","M3_CONVERSATION_TO_TICKET","M3_CUSTOMER_TAG_ADD","M3_CUSTOMER_TAG_REMOVE",
            "M3_CUSTOMER_NOTE_ADD","M3_CUSTOMER_NOTE_REMOVE","M2_SUPPORT_TICKET_CREATE","M2_SUPPORT_TICKET_REPLY","M2_SUPPORT_TICKET_ESCALATE",
            "M3_SUPPORT_BULK_CREATE","M3_SUPPORT_BULK_CANCEL","M3_SUPPORT_BULK_RETRY");

    @GetMapping("/api/admin/content/support-workbench/commands/{key}")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m2_read','service_m3_read')")
    @Transactional
    public ApiResult<Map<String,Object>> recover(@PathVariable String key) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(key.trim().length()<8 || key.length()>128) throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
        long actor=ownership.actorId();
        var found=records.selectSupportCommand(SCOPES.stream().map(s->s+":"+actor).toList(),key.trim());
        if(found.isEmpty()) {
            var restored=bulk.recover(key);
            if("SUCCEEDED".equals(restored.get("status"))) return ApiResult.ok(restored);
            throw new BizException(404,"SUPPORT_COMMAND_NOT_FOUND");
        }
        if(found.size()!=1) throw new BizException(409,"SUPPORT_COMMAND_AMBIGUOUS");
        var receipt=found.get(0);
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
        customers.forEach(ownership::requireRead);
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
