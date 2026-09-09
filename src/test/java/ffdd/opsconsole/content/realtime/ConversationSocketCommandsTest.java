package ffdd.opsconsole.content.realtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.web.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSocketCommandsTest {
    ObjectMapper json=new ObjectMapper();
    AppSupportController app=mock(AppSupportController.class);
    OpsConversationController admin=mock(OpsConversationController.class);
    ConversationAdminReadController reads=mock(ConversationAdminReadController.class);
    ConversationSocketAccess access=mock(ConversationSocketAccess.class);
    ConversationSocketCommands commands=new ConversationSocketCommands(app,admin,reads,access,json);
    UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("12",null,List.of());
    @Test void missingKeyCannotInvokeACommand() {
        assertThrows(BizException.class,()->commands.execute(auth,"ADMIN",json.valueToTree(Map.of("operation","create","body",Map.of()))));
        verifyNoInteractions(admin);
    }
    @Test void readOnlyRoleCannotAcknowledgeMessages() {
        when(access.canRead(auth,"ADMIN","CV-1")).thenReturn(true);
        doThrow(new BizException(403,"denied")).when(access).write(auth,"ADMIN");
        assertThrows(BizException.class,()->commands.execute(auth,"ADMIN",json.valueToTree(Map.of("operation","read","conversationNo","CV-1","body",Map.of("lastSeenMessageId",1)))));
        verifyNoInteractions(reads);
    }
    @Test void routesTheStableKeyThroughTheControllerAndRestoresThreadIdentityOnFailure() {
        var previous=SecurityContextHolder.getContext();
        when(access.canRead(auth,"USER","CV-1")).thenReturn(true);
        when(app.replyConversation(eq("CV-1"),eq("stable-key"),any(),eq(auth))).thenAnswer(a->{
            assertSame(auth,SecurityContextHolder.getContext().getAuthentication());throw new BizException(409,"conflict");
        });
        assertThrows(BizException.class,()->commands.execute(auth,"USER",json.valueToTree(Map.of("operation","reply","conversationNo","CV-1","idempotencyKey","stable-key","body",Map.of("body","hi","expectedStatus","OPEN","expectedVersion",1)))));
        assertSame(previous,SecurityContextHolder.getContext());
        verify(app).replyConversation(eq("CV-1"),eq("stable-key"),any(),eq(auth));
    }
}
