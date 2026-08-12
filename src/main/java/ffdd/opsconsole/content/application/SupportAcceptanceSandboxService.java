package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import ffdd.opsconsole.content.domain.SupportAcceptanceSandboxObservationWindow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent acceptance facts only; no production support repository is injected here. */
@Service
@Profile({"acceptance", "test", "local-sandbox"})
@RequiredArgsConstructor
public class SupportAcceptanceSandboxService {
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SupportAcceptanceSandboxProfileGuard guard;
    private final SupportAcceptanceSandboxMapper mapper;
    private final Clock clock;
    @Value("${nexion.support.acceptance-run-id:}")
    private final String configuredRunId;

    public Map<String,Object> proof(Long user) { requireSandboxUser(user); return proofFor(run(user)); }
    public Map<String,Object> adminProof() { guard.requireAvailable(); return proofFor(run(null)); }

    private Map<String,Object> proofFor(String runId) {
        SupportAcceptanceSandboxObservationWindow window = mapper.observationWindow(runId);
        Map<String,Object> proof = new LinkedHashMap<>();
        proof.put("source", "mock");
        proof.put("sourceEnvironment", "SANDBOX");
        proof.put("strictProfile", true);
        proof.put("runId", runId);
        proof.put("permanentLabel", "ACCEPTANCE SANDBOX • NON-PRODUCTION");
        proof.put("productionDelta", productionDelta(runId, window));
        return proof;
    }

    private Map<String,Object> productionDelta(String runId, SupportAcceptanceSandboxObservationWindow window) {
        if (window == null || !window.available()) {
            return Map.of("status", "INSUFFICIENT", "sandboxFacts", window == null ? 0 : window.facts(),
                    "sandboxAccounts", window == null ? 0 : window.sandboxAccounts());
        }
        int ticket = mapper.productionTicketDelta(runId, window.fromAt(), window.toAt());
        int ticketMessage = mapper.productionTicketMessageDelta(runId, window.fromAt(), window.toAt());
        int conversation = mapper.productionConversationDelta(runId, window.fromAt(), window.toAt());
        int conversationMessage = mapper.productionConversationMessageDelta(runId, window.fromAt(), window.toAt());
        int receipt = mapper.productionReceiptDelta(runId, window.fromAt(), window.toAt());
        int audit = mapper.productionAuditDelta(runId, window.fromAt(), window.toAt());
        int idempotency = mapper.productionIdempotencyDelta(runId, window.fromAt(), window.toAt());
        int outbox = mapper.productionOutboxDelta(runId, window.fromAt(), window.toAt());
        String status = ticket == 0 && ticketMessage == 0 && conversation == 0 && conversationMessage == 0
                && receipt == 0 && audit == 0 && idempotency == 0 && outbox == 0 ? "VERIFIED_ZERO" : "VIOLATION";
        Map<String,Object> delta = new LinkedHashMap<>();
        delta.put("status", status); delta.put("sandboxFacts", window.facts()); delta.put("sandboxAccounts", window.sandboxAccounts());
        delta.put("fromAt", window.fromAt()); delta.put("toAt", window.toAt());
        delta.put("ticket", ticket); delta.put("ticketMessage", ticketMessage); delta.put("conversation", conversation);
        delta.put("conversationMessage", conversationMessage); delta.put("receipt", receipt); delta.put("audit", audit);
        delta.put("idempotency", idempotency); delta.put("outbox", outbox);
        return delta;
    }
    public Map<String,Object> tickets(Long user) { requireSandboxUser(user); return Map.of("total", mapper.tickets(run(user), user).size(), "pageNum",1,"pageSize",100,"records",mapper.tickets(run(user),user)); }
    public Map<String,Object> conversations(Long user) { requireSandboxUser(user); return Map.of("total", mapper.conversations(run(user), user).size(), "pageNum",1,"pageSize",100,"records",mapper.conversations(run(user),user)); }
    public Map<String,Object> ticket(Long user,String id) { requireSandboxUser(user); return ticketDetail(user,id); }
    public Map<String,Object> conversation(Long user,String id) { requireSandboxUser(user); return conversationDetail(user,id); }

    @Transactional
    public Map<String,Object> createTicket(Long user,String key,Map<String,Object> body) {
        return command(user,key,"TICKET_CREATE",key,body.toString(),"app",() -> { String id=no("ATK-"); LocalDateTime now=now(); ensure(user,now);
            mapper.createTicket(id,run(user),user,text(body,"category"),text(body,"title"),now); mapper.ticketMessage(id,run(user),user,text(body,"body"),key,now); return result("ticket",id); });
    }
    @Transactional
    public Map<String,Object> replyTicket(Long user,String id,String key,Map<String,Object> body) {
        return command(user,key,"TICKET_REPLY",id,body.toString(),"app",() -> { Map<String,Object> t=required(mapper.ticket(run(user),user,id)); requireReplyableTicket(t); cas(mapper.ticketCas(id,run(user),user,upper(body,"expectedStatus"),number(body,"expectedVersion"),upper(body,"expectedStatus"),now())); mapper.ticketMessage(id,run(user),user,text(body,"body"),key,now()); return result("ticket",id); });
    }
    @Transactional
    public Map<String,Object> closeTicket(Long user,String id,String key,Map<String,Object> body) {
        return command(user,key,"TICKET_CLOSE",id,body.toString(),"app",() -> { Map<String,Object> t=required(mapper.ticket(run(user),user,id)); requireReplyableTicket(t); cas(mapper.ticketCas(id,run(user),user,upper(body,"expectedStatus"),number(body,"expectedVersion"),"CLOSED",now())); return result("ticket",id); });
    }
    @Transactional
    public Map<String,Object> startConversation(Long user,String key,Map<String,Object> body) {
        return command(user,key,"CONVERSATION_CREATE",key,body.toString(),"app",() -> { String id=no("ACV-"); LocalDateTime now=now(); ensure(user,now); mapper.createConversation(id,run(user),user,text(body,"conversationType").toLowerCase(),text(body,"openingText"),now); mapper.conversationMessage(id,run(user),user,text(body,"openingText"),key,now); return result("conversation",id); });
    }
    @Transactional
    public Map<String,Object> replyConversation(Long user,String id,String key,Map<String,Object> body) {
        return command(user,key,"CONVERSATION_REPLY",id,body.toString(),"app",() -> { Map<String,Object> c=required(mapper.conversation(run(user),user,id)); requireOperableConversation(c); cas(mapper.conversationCas(id,run(user),user,upper(body,"expectedStatus"),number(body,"expectedVersion"),upper(body,"expectedStatus"),text(body,"body"),now())); mapper.conversationMessage(id,run(user),user,text(body,"body"),key,now()); return result("conversation",id); });
    }
    @Transactional
    public Map<String,Object> read(Long user,String id,Map<String,Object> body) {
        LocalDateTime at=now(); ensure(user,at); String runId=run(user); required(mapper.conversation(runId,user,id));
        String status=upper(body,"expectedStatus"); Long version=number(body,"expectedVersion"); Long lastSeen=number(body,"lastSeenMessageId");
        if(lastSeen<=0||mapper.agentMessageExists(runId,user,id,lastSeen)!=1)throw new BizException(404,"CONVERSATION_AGENT_MESSAGE_NOT_FOUND");
        // The exact scoped authority lookup happens before the header CAS.  readCas repeats it in SQL,
        // so a cross-conversation/user/user-message id can never clear unread_count or create receipts.
        cas(mapper.readHeaderCas(id,runId,user,status,version,at));
        if(mapper.readCas(id,runId,user,lastSeen,status,version,"user:"+user,at)<=0)throw new BizException(409,"SUPPORT_ACCEPTANCE_CONFLICT");
        return conversationDetail(user,id);
    }
    @Transactional
    public Map<String,Object> toTicket(Long user,String id,String key,Map<String,Object> body) {
        return command(user,key,"CONVERSATION_TO_TICKET",id,body.toString(),"app",() -> { Map<String,Object> c=required(mapper.conversation(run(user),user,id)); requireNotClosedConversation(c); cas(mapper.conversationCas(id,run(user),user,upper(body,"expectedStatus"),number(body,"expectedVersion"),"CLOSED","Converted to ticket",now())); String ticket=no("ATK-"); mapper.createTicket(ticket,run(user),user,text(body,"category"),text(body,"title"),now()); return result("conversation-ticket",id+":"+ticket); });
    }
    public Map<String,Object> commandResult(Long user,String key) { guard.requireAvailable(); requireSandboxUser(user); Map<String,Object> c=mapper.command(key,run(user),user); if(c==null) return null; return json(String.valueOf(c.get("resultJson"))); }
    public Map<String,Object> adminCommandResult(String key) { guard.requireAvailable(); Map<String,Object> c=mapper.adminCommand(key,run(null)); return c==null?null:json(String.valueOf(c.get("resultJson"))); }
    public List<Map<String,Object>> adminConversations() { guard.requireAvailable(); return mapper.adminConversations(run(null)); }
    public List<Map<String,Object>> adminTickets() { guard.requireAvailable(); return mapper.adminTickets(run(null)); }
    public Map<String,Object> adminTicket(String id) { guard.requireAvailable(); return required(mapper.adminTicket(run(null),id)); }
    @Transactional public Map<String,Object> adminReply(String id,String key,Map<String,Object> body) { guard.requireAvailable(); Map<String,Object> c=requiredAdmin(id); Long u=((Number)c.get("accountId")).longValue(); String reason=text(body,"reason"); return command(u,key,"OPS_REPLY",id,body.toString(),reason,()->{requireOperableConversation(c);cas(mapper.agentReplyCas(id,run(u),u,upper(body,"expectedStatus"),number(body,"expectedVersion"),text(body,"body"),now()));mapper.agentConversationMessage(id,run(u),u,text(body,"agentName"),text(body,"body"),key,now());return result("conversation",id);}); }
    @Transactional public Map<String,Object> adminTransfer(String id,String key,Map<String,Object> body) { guard.requireAvailable(); Map<String,Object> c=requiredAdmin(id); Long u=((Number)c.get("accountId")).longValue(); String reason=text(body,"reason"); return command(u,key,"OPS_TRANSFER",id,body.toString(),reason,()->{requireTransferableConversation(c);cas(mapper.transferCas(id,run(u),u,upper(body,"expectedStatus"),number(body,"expectedVersion"),text(body,"targetAgentId"),text(body,"targetAgentName"),now()));return result("conversation",id);}); }
    @Transactional public Map<String,Object> adminTicketReply(String id,String key,Map<String,Object> body) { guard.requireAvailable(); Map<String,Object> t=requiredAdminTicket(id); Long u=((Number)t.get("accountId")).longValue(); String reason=text(body,"reason"); return command(u,key,"OPS_TICKET_REPLY",id,body.toString(),reason,()->{requireReplyableTicket(t);cas(mapper.ticketCas(id,run(u),u,upper(body,"expectedStatus"),number(body,"expectedVersion"),upper(body,"expectedStatus"),now()));mapper.agentTicketMessage(id,run(u),u,text(body,"agentName"),text(body,"body"),key,now());return result("ticket",id);}); }
    @Transactional public Map<String,Object> adminTicketClose(String id,String key,Map<String,Object> body) { guard.requireAvailable(); Map<String,Object> t=requiredAdminTicket(id); Long u=((Number)t.get("accountId")).longValue(); String reason=text(body,"reason"); return command(u,key,"OPS_TICKET_CLOSE",id,body.toString(),reason,()->{requireReplyableTicket(t);cas(mapper.ticketCas(id,run(u),u,upper(body,"expectedStatus"),number(body,"expectedVersion"),"CLOSED",now()));return result("ticket",id);}); }
    private Map<String,Object> command(Long u,String key,String type,String business,String payload,String reason,java.util.function.Supplier<Map<String,Object>> write) { guard.requireAvailable(); ensure(u,now()); String payloadHash=hash(type+"|"+business+"|"+payload); Map<String,Object> prior=mapper.command(key,run(u),u); if(prior!=null){if(!payloadHash.equals(String.valueOf(prior.get("payloadHash"))))throw new BizException(409,"SUPPORT_ACCEPTANCE_IDEMPOTENCY_PAYLOAD_CONFLICT");return json(String.valueOf(prior.get("resultJson")));} Map<String,Object> r=write.get(); Map<String,Object> response=render(String.valueOf(r.get("resultType")),String.valueOf(r.get("resultId")),u); mapper.commandInsert(key,run(u),u,type,business,reason,payloadHash,jsonText(response),String.valueOf(r.get("resultType")),String.valueOf(r.get("resultId")),now()); return response; }
    private Map<String,Object> render(String type,String id,Long u) { if("ticket".equals(type))return Map.of("resultType",type,"result",ticketDetail(u,id)); if("conversation".equals(type))return Map.of("resultType",type,"result",conversationDetail(u,id)); String[] p=id.split(":",2); return Map.of("resultType",type,"result",Map.of("conversation",conversationDetail(u,p[0]).get("conversation"),"ticket",ticketDetail(u,p[1]))); }
    private Map<String,Object> ticketDetail(Long u,String id){Map<String,Object> t=required(mapper.ticket(run(u),u,id));return Map.of("ticket",t,"messages",mapper.ticketMessages(run(u),u,id));}
    private Map<String,Object> conversationDetail(Long u,String id){Map<String,Object> c=required(mapper.conversation(run(u),u,id));return Map.of("conversation",c,"messages",mapper.conversationMessages(run(u),u,id));}
    private Map<String,Object> requiredAdmin(String id){return mapper.adminConversations(run(null)).stream().filter(c->id.equals(c.get("conversationNo"))).findFirst().orElseThrow(()->new BizException(404,"SUPPORT_ACCEPTANCE_NOT_FOUND"));} private Map<String,Object> requiredAdminTicket(String id){return mapper.adminTickets(run(null)).stream().filter(c->id.equals(c.get("ticketNo"))).findFirst().orElseThrow(()->new BizException(404,"SUPPORT_ACCEPTANCE_NOT_FOUND"));} private void ensure(Long u,LocalDateTime n){requireSandboxUser(u);mapper.ensureRun(run(u),u,n);} private void requireSandboxUser(Long u){guard.requireAvailable();if(u==null||mapper.sandboxUser(u)==null||mapper.sandboxUser(u)!=1)throw new BizException(403,"SUPPORT_ACCEPTANCE_SANDBOX_USER_REQUIRED");} private String run(Long u){if(configuredRunId==null||configuredRunId.trim().isEmpty())throw new BizException(409,"SUPPORT_ACCEPTANCE_RUN_ID_REQUIRED");return configuredRunId.trim();} private LocalDateTime now(){return LocalDateTime.now(clock);} private String no(String p){return p+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();} private Map<String,Object> required(Map<String,Object> v){if(v==null)throw new BizException(404,"SUPPORT_ACCEPTANCE_NOT_FOUND");return v;} private void requireReplyableTicket(Map<String,Object> ticket){String status=upper(ticket,"status");if(!"OPEN".equals(status)&&!"RESOLVED".equals(status))throw new BizException(409,"SUPPORT_TICKET_INVALID_STATE");} private void requireOperableConversation(Map<String,Object> conversation){String status=upper(conversation,"status");if(!"OPEN".equals(status)&&!"RESOLVED".equals(status))throw new BizException(409,"CONVERSATION_INVALID_STATE");} private void requireTransferableConversation(Map<String,Object> conversation){if(!"OPEN".equals(upper(conversation,"status")))throw new BizException(409,"CONVERSATION_INVALID_STATE");} private void requireNotClosedConversation(Map<String,Object> conversation){if("CLOSED".equals(upper(conversation,"status")))throw new BizException(409,"CONVERSATION_INVALID_STATE");} private void cas(int n){if(n!=1)throw new BizException(409,"SUPPORT_ACCEPTANCE_CONFLICT");} private String text(Map<String,Object> m,String k){Object v=m==null?null:m.get(k);if(v==null||String.valueOf(v).trim().isEmpty())throw new BizException(422,"SUPPORT_ACCEPTANCE_INPUT_INVALID");return String.valueOf(v).trim();} private String upper(Map<String,Object> m,String k){return text(m,k).toUpperCase();} private Long number(Map<String,Object> m,String k){try{return Long.valueOf(text(m,k));}catch(NumberFormatException e){throw new BizException(422,"SUPPORT_ACCEPTANCE_INPUT_INVALID");}} private String hash(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}} private String jsonText(Map<String,Object> value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}} private Map<String,Object> json(String value){try{return JSON.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new BizException(409,"SUPPORT_ACCEPTANCE_IDEMPOTENCY_RESULT_INVALID");}}
    private Map<String,Object> result(String type,String id){return Map.of("resultType",type,"resultId",id);}
}
