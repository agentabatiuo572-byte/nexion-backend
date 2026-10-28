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
    @Test void retiredIdentifiersCannotRecreateTicketDeskPermissionsButSeatCapacityPathStaysRegistrable() {
        var mapper=mock(AdminPermissionMapper.class);
        var idempotency=mock(AdminIdempotencyService.class);
        var service=new OpsA1PermissionRegistrationService(mapper,idempotency,mock(AuditLogService.class));
        for(String[] entry:new String[][]{{"service_m2_read","/api/admin/other"},{"service_m2_write","/api/admin/other"},
                {"custom_read","/api/admin/content/tickets"},{"custom_read","/api/admin/content/tickets/TK-1/replies"}}) {
            var result=service.register("retired-ticket-test",new A1PermissionRegistrationRequest(entry[0],"Retired",entry[1],
                    "READ",false,true,"retirement regression","superadmin"));
            assertThat(result.getCode()).as(entry[0]+" "+entry[1]).isEqualTo(422);
            assertThat(result.getMessage()).isEqualTo("A1_PERMISSION_RETIRED");
        }
        verifyNoInteractions(mapper,idempotency);
        // The M1 seat-capacity path passes validation and reaches the idempotent registration command.
        service.register("seat-capacity-test",new A1PermissionRegistrationRequest("custom_seat_read","Seat",
                "/api/admin/content/tickets/load-config","READ",false,true,"seat capacity regression","superadmin"));
        assertThat(org.mockito.Mockito.mockingDetails(idempotency).getInvocations()).hasSize(1);
    }
}
