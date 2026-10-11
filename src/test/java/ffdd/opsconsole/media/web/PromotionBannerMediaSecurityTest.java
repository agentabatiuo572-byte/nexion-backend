package ffdd.opsconsole.media.web;

import ffdd.opsconsole.media.application.OpsMediaUploadService;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.promotion.application.PromotionBannerService;
import ffdd.opsconsole.promotion.web.PromotionBannerController;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({OpsMediaController.class, PromotionBannerController.class})
@ActiveProfiles("dev")
@Import(SecurityConfig.class)
@ContextConfiguration(classes = {OpsMediaController.class, PromotionBannerController.class, SecurityConfig.class, AdminRbacAuthorizationFilter.class})
class PromotionBannerMediaSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean(name="opsMediaUploadService") OpsMediaUploadService opsMediaUploadService;
    @MockBean PromotionBannerService banners;
    @MockBean JwtAuthenticationFilter jwt;
    @MockBean AuditLogService audit;
    @MockBean AdminAccountStateMapper accountStates;

    @BeforeEach void setup() throws Exception {
        doAnswer(i -> { i.getArgument(2, jakarta.servlet.FilterChain.class).doFilter(i.getArgument(0), i.getArgument(1)); return null; }).when(jwt).doFilter(any(), any(), any());
        when(opsMediaUploadService.isSkuAsset("sku")).thenReturn(true);
        when(opsMediaUploadService.isPromotionBannerAsset("banner")).thenReturn(true);
        when(opsMediaUploadService.refreshPreviewUrl(anyString())).thenReturn(new UploadedAsset("id", "key", "bucket", "image/png", 8L, "https://storage/preview", null, "growth", "promotion-banner"));
    }

    @Test void growthEditorUploadsOnlyPromotionArtworkThroughRealPrefixAndMethodGuards() throws Exception {
        upload("growth_promotion_edit", "growth", "promotion-banner", 200);
        upload("growth_promotion_edit", "growth", "promotion-evidence", 403);
        upload("growth_promotion_edit", "e", "sku-image", 403);
        upload("growth_promotion_read", "growth", "promotion-banner", 403);
        upload("device_e1_write", "e", "sku-image", 200);
        mvc.perform(multipart("/api/admin/media/uploads").file(file()).param("domain", "growth").param("usage", "promotion-banner"))
                .andExpect(status().isUnauthorized());
        verify(opsMediaUploadService, times(2)).upload(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void previewPermissionsDoNotCrossBannerSkuOrEvidenceNamespaces() throws Exception {
        for (String permission : new String[] {"growth_promotion_read", "growth_promotion_edit"}) {
            preview(permission, "banner", 200);
            preview(permission, "sku", 403);
            preview(permission, "evidence", 403);
        }
        preview("device_e1_read", "sku", 200);
        preview("device_e1_read", "banner", 403);
        preview("growth_promotion_publish", "banner", 403);
        verify(opsMediaUploadService, times(3)).refreshPreviewUrl(anyString());
    }

    @Test void anonymousBannerResponseIsNoStoreAndNosniffWithoutOpeningAdminMedia() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(banners.read("PROMO-1", 2)).thenReturn(new PromotionBannerService.ImageBytes("image/png", bytes));
        mvc.perform(get("/api/promotions/PROMO-1/banner/2")).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/admin/media/uploads/banner/preview-url")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/promotions/PROMO-1/banner/2")).andExpect(status().isUnauthorized());
    }

    private void upload(String permission, String domain, String usage, int expected) throws Exception {
        mvc.perform(multipart("/api/admin/media/uploads").file(file()).param("domain", domain).param("usage", usage)
                .header("Idempotency-Key", "media-1").with(user("operator").authorities(new SimpleGrantedAuthority(permission))))
                .andExpect(status().is(expected));
    }
    private void preview(String permission, String asset, int expected) throws Exception {
        mvc.perform(get("/api/admin/media/uploads/" + asset + "/preview-url").with(user("operator").authorities(new SimpleGrantedAuthority(permission))))
                .andExpect(status().is(expected));
    }
    private static MockMultipartFile file() { return new MockMultipartFile("file", "banner.png", "image/png", new byte[] {1}); }
}
