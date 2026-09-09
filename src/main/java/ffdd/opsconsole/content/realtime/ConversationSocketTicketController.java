package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;

@RestController
@RequiredArgsConstructor
public class ConversationSocketTicketController {
    private final ConversationSocketTickets tickets;
    @PostMapping("/api/app/support/realtime-ticket")
    public ResponseEntity<ApiResult<Map<String, String>>> app(@RequestHeader("Authorization") String bearer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(Map.of("ticket", tickets.issue(bearer, "USER"), "path", "/ws/conversations")));
    }
    @PostMapping("/api/admin/content/conversations/realtime-ticket")
    @PreAuthorize("hasAuthority('service_m3_read')")
    public ResponseEntity<ApiResult<Map<String, String>>> admin(@RequestHeader("Authorization") String bearer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(Map.of("ticket", tickets.issue(bearer, "ADMIN"), "path", "/ws/conversations")));
    }
}
