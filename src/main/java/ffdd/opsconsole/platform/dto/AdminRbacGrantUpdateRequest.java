package ffdd.opsconsole.platform.dto;

import java.util.List;

public record AdminRbacGrantUpdateRequest(
        List<String> grants,
        String reason,
        String operator) {
}
