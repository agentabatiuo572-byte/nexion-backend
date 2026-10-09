package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportLeaderboardControllerTest {
    @Test void fixedThreeGetRoutesRequireExactSupportReadAuthoritiesAndProductionGuard() throws Exception {
        assertEquals("/api/admin/content/support-workbench/leaderboard",SupportLeaderboardController.class.getAnnotation(RequestMapping.class).value()[0]);
        for(var method:SupportLeaderboardController.class.getDeclaredMethods()){
            assertNotNull(method.getAnnotation(GetMapping.class));assertEquals("hasAnyAuthority('service_m1_read','service_m3_read')",method.getAnnotation(PreAuthorize.class).value());
        }
        var service=mock(SupportLeaderboardService.class);var guard=mock(ProductionSupportPathGuard.class);var controller=new SupportLeaderboardController(service,guard);
        doThrow(new BizException(409,"SUPPORT_PRODUCTION_PATH_FORBIDDEN")).when(guard).requireOpsWriteAllowed();
        assertThrows(BizException.class,()->controller.page(new LinkedMultiValueMap<>()));assertThrows(BizException.class,()->controller.detail(9,new LinkedMultiValueMap<>()));
        assertThrows(BizException.class,()->controller.avatar(9,new LinkedMultiValueMap<>()));verifyNoInteractions(service);
    }
    @Test void rawMultimapIsPreservedAndAvatarUsesExistingNoStoreNosniffHeaders(){var service=mock(SupportLeaderboardService.class);var guard=mock(ProductionSupportPathGuard.class);
        var controller=new SupportLeaderboardController(service,guard);var raw=new LinkedMultiValueMap<String,String>();raw.add("board","firstPayment");raw.add("board","deposit");
        when(service.page(raw)).thenReturn(Map.of("rows",java.util.List.of()));assertEquals(0,controller.page(raw).getCode());verify(service).page(raw);
        when(service.avatar(raw,9)).thenReturn(new SupportAttachmentService.Content("image/png",new byte[]{1}));var image=controller.avatar(9,raw);
        assertEquals("no-store",image.getHeaders().getCacheControl());assertEquals("nosniff",image.getHeaders().getFirst("X-Content-Type-Options"));assertArrayEquals(new byte[]{1},image.getBody());
    }
}
