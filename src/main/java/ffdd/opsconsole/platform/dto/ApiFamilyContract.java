package ffdd.opsconsole.platform.dto;

import java.util.List;

public record ApiFamilyContract(
        String resource,
        String path,
        String readPermission,
        String writePermission,
        boolean idempotencyRequired,
        boolean reasonRequired,
        boolean auditRequired,
        boolean b1RedlineTriggered,
        List<String> errorCodes) {
}
