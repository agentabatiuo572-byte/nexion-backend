package ffdd.opsconsole.content.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.content.web.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Calls Spring proxies so command idempotency, method security and transactions remain authoritative. */
@Component
@RequiredArgsConstructor
public class ConversationSocketCommands {
    private final AppSupportController app;
    private final OpsConversationController admin;
    private final ConversationAdminReadController reads;
    private final ConversationSocketAccess access;
    private final ObjectMapper json;

    public ApiResult<?> execute(Authentication auth,String audience,JsonNode frame) {
        String op=frame.path("operation").asText();
        String no=frame.path("conversationNo").asText();
        String key=frame.path("idempotencyKey").asText(null);
        JsonNode body=frame.path("body");
        if (!"create".equals(op) && !access.canRead(auth,audience,no)) throw new BizException(404,"CONVERSATION_NOT_FOUND");
        access.write(auth,audience);
        if (("create".equals(op)||"reply".equals(op)) && (key==null || !key.matches("[A-Za-z0-9_-]{8,128}")))
            throw new BizException(400,"IDEMPOTENCY_KEY_REQUIRED");
        var previous=SecurityContextHolder.getContext();
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            if("USER".equals(audience)) return switch(op) {
                case "create" -> app.startConversation(key,json.convertValue(body,AppSupportService.StartConversationRequest.class),auth);
                case "reply" -> app.replyConversation(no,key,json.convertValue(body,AppSupportService.ReplyRequest.class),auth);
                case "read" -> app.markConversationRead(no,json.convertValue(body,AppSupportController.MarkReadRequest.class),auth);
                default -> throw new BizException(400,"UNKNOWN_SOCKET_COMMAND");
            };
            return switch(op) {
                case "create" -> admin.initiate(key,json.convertValue(body,ConversationInitiateRequest.class));
                case "reply" -> admin.reply(no,key,json.convertValue(body,ConversationReplyRequest.class));
                case "read" -> reads.read(no,json.convertValue(body,AppSupportController.MarkReadRequest.class),auth);
                default -> throw new BizException(400,"UNKNOWN_SOCKET_COMMAND");
            };
        } finally { SecurityContextHolder.setContext(previous); }
    }
}
