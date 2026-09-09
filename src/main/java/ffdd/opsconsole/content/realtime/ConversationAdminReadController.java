package ffdd.opsconsole.content.realtime;
import ffdd.opsconsole.content.web.AppSupportController.MarkReadRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
public class ConversationAdminReadController {
    private final ConversationAdminReadService service;
    @PostMapping("/api/admin/content/conversations/{conversationNo}/read")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Void> read(@PathVariable String conversationNo,@RequestBody MarkReadRequest request, Authentication auth) {
        return service.read(conversationNo,request,auth.getName());
    }
}
