package ffdd.compliance.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.compliance.dto.KycExpiryResult;
import ffdd.compliance.service.KycProfileService;
import org.junit.jupiter.api.Test;

class KycExpiryWorkerTest {
    private final KycProfileService kycProfileService = mock(KycProfileService.class);

    @Test
    void skipsWhenDisabled() {
        KycExpiryWorker worker = new KycExpiryWorker(kycProfileService, false, 50, "system");

        worker.expireScheduled();

        verify(kycProfileService, never()).expireApprovedProfiles(50, "system");
    }

    @Test
    void expiresBatchWhenEnabled() {
        KycExpiryWorker worker = new KycExpiryWorker(kycProfileService, true, 50, "system");
        when(kycProfileService.expireApprovedProfiles(50, "system")).thenReturn(new KycExpiryResult());

        worker.expireScheduled();

        verify(kycProfileService).expireApprovedProfiles(50, "system");
    }
}
