package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.PublicSponsorPreviewService;
import ffdd.opsconsole.growth.domain.PublicSponsorPreviewView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PublicSponsorPreviewControllerTest {
    private final PublicSponsorPreviewService service = mock(PublicSponsorPreviewService.class);
    private final PublicSponsorPreviewController controller = new PublicSponsorPreviewController(service);

    @Test
    void publicPreviewDelegatesCanonicalPathWithoutAuthentication() {
        var view = new PublicSponsorPreviewView("NXAB12CD34EF", "PRODUCTION",
                new PublicSponsorPreviewView.Sponsor("A•••", "V3"),
                new PublicSponsorPreviewView.Gift("PENDING_REVIEW", new BigDecimal("1.25"), new BigDecimal("20")));
        when(service.preview("NXAB12CD34EF")).thenReturn(ApiResult.ok(view));

        ApiResult<PublicSponsorPreviewView> result = controller.preview("NXAB12CD34EF");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(view);
        verify(service).preview("NXAB12CD34EF");
    }

    @Test
    void nullPathIsStillFailClosedByService() {
        when(service.preview(null)).thenReturn(ApiResult.fail(422, "REFERRAL_PREVIEW_INVALID"));

        assertThat(controller.preview(null).getCode()).isEqualTo(422);
        verify(service).preview(null);
    }
}
