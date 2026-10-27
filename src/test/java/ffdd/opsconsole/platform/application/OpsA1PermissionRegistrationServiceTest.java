package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.platform.dto.A1PermissionRegistrationRequest;
import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import org.junit.jupiter.api.Test;

class OpsA1PermissionRegistrationServiceTest {
    @Test void retiredIdentifiersCannotRecreateJanusPermissions() {
        var mapper=mock(AdminPermissionMapper.class);
        var idempotency=mock(AdminIdempotencyService.class);
        var service=new OpsA1PermissionRegistrationService(mapper,idempotency,mock(AuditLogService.class));
        for(String[] entry:new String[][]{{"risk_k6_read","/api/admin/other"},
                {"custom_read","/api/admin/janus"},{"custom_read","/api/admin/janus/commands"}}) {
            var result=service.register("retired-test",new A1PermissionRegistrationRequest(entry[0],"Retired",entry[1],
                    "READ",false,true,"retirement regression","superadmin"));
            assertThat(result.getCode()).isEqualTo(422);
            assertThat(result.getMessage()).isEqualTo("A1_PERMISSION_RETIRED");
        }
        verifyNoInteractions(mapper,idempotency);
    }
}
