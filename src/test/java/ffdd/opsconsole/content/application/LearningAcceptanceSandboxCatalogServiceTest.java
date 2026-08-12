package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LearningAcceptanceSandboxCatalogServiceTest {
    private final LearningAcceptanceSandboxGate gate = mock(LearningAcceptanceSandboxGate.class);
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final LearningAcceptanceSandboxCatalogService service = new LearningAcceptanceSandboxCatalogService(gate, mapper);

    @Test
    void savesAndPublishesOnlyTheRunScopedSandboxCatalog() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.eq("acceptance.2026-08"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(mapper.insertSandboxCourse(org.mockito.ArgumentMatchers.eq("acceptance.2026-08"), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v1", 0)).thenReturn(1);
        LearningCourseUpsertRequest request = new LearningCourseUpsertRequest("沙箱课程", "Sandbox course", "正文", "Body", "Basics", "Article", "Beginner", BigDecimal.TEN, "5 min", "draft", "acceptance", "verify");

        assertThat(service.saveDraft("acceptance.2026-08", "sandbox-course", request, "sandbox-catalog-save-001").getCode()).isZero();
        assertThat(service.publish("acceptance.2026-08", "sandbox-course", "v1", 0, "sandbox-catalog-publish-001").getCode()).isZero();

        verify(mapper).insertSandboxCourse(org.mockito.ArgumentMatchers.eq("acceptance.2026-08"), org.mockito.ArgumentMatchers.any());
        verify(mapper).publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v1", 0);
        verify(mapper, org.mockito.Mockito.times(2)).claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.eq("acceptance.2026-08"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void completedSandboxAdminKeyReturnsItsOriginalResultBeforeItCanMutateAgain() throws Exception {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
        LearningCourseUpsertRequest request = new LearningCourseUpsertRequest("沙箱课程", "Sandbox course", "正文", "Body", "Basics", "Article", "Beginner", BigDecimal.TEN, "5 min", "draft", "acceptance", "verify");
        String requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        LearningSandboxCourseRow original = new LearningSandboxCourseRow("sandbox-course", "v1", "DRAFT", "沙箱课程", "Sandbox course", "",
                "正文", "Body", "", "Basics", "Article", "Beginner", BigDecimal.TEN, "5 min", false, "[]", null, null, "", "", 0L);
        when(mapper.lockSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LearningSandboxIdempotencyRow(requestHash, "COMPLETED", new ObjectMapper().writeValueAsString(java.util.Map.of("code", 0, "message", "OK", "data", original))));

        var replay = service.saveDraft("acceptance.2026-08", "sandbox-course", request, "sandbox-catalog-save-001");
        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData()).isEqualTo(original);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertSandboxCourse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateSandboxCourseDraft(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void commandResultReadsTheDurableRunScopedReceipt() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.findSandboxCatalogCommandResult("acceptance.2026-08", "LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1", "sandbox-catalog-publish-001"))
                .thenReturn(new LearningSandboxIdempotencyRow("hash", "COMPLETED", "{\"code\":0,\"message\":\"OK\",\"data\":{}}"));

        assertThat(service.commandResult("acceptance.2026-08", "LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1", "sandbox-catalog-publish-001").getCode()).isZero();
        verify(mapper).findSandboxCatalogCommandResult("acceptance.2026-08", "LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1", "sandbox-catalog-publish-001");
    }

    @Test
    void commandResultMakesDurableBusinessFailureExplicitRatherThanSuccessByPresence() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.findSandboxCatalogCommandResult("acceptance.2026-08", "LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1", "sandbox-catalog-publish-001"))
                .thenReturn(new LearningSandboxIdempotencyRow("hash", "COMPLETED", "{\"code\":409,\"message\":\"LEARNING_SANDBOX_COURSE_NOT_DRAFT\",\"data\":null}"));

        var receipt = service.commandResult("acceptance.2026-08", "LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1", "sandbox-catalog-publish-001");

        assertThat(receipt.getCode()).isZero();
        assertThat(receipt.getData()).containsEntry("committed", true).containsEntry("succeeded", false).containsEntry("code", 409);
    }

    @Test
    void sameSandboxAdminKeyWithDifferentPayloadFailsClosed() throws Exception {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        LearningCourseUpsertRequest request = new LearningCourseUpsertRequest("沙箱课程", "Sandbox course", "正文", "Body", "Basics", "Article", "Beginner", BigDecimal.TEN, "5 min", "draft", "acceptance", "verify");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
        when(mapper.lockSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LearningSandboxIdempotencyRow("not-the-request-hash", "COMPLETED", "{}"));

        assertThatThrownBy(() -> service.saveDraft("acceptance.2026-08", "sandbox-course", request, "sandbox-catalog-save-001"))
                .hasMessage("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_PAYLOAD_MISMATCH");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertSandboxCourse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void businessConflictIsCompletedAsADurableReceiptInsteadOfLeavingPending() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(mapper.publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v1", 0)).thenReturn(0);

        assertThat(service.publish("acceptance.2026-08", "sandbox-course", "v1", 0, "sandbox-catalog-publish-001").getCode()).isEqualTo(409);
        verify(mapper).completeSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.eq("acceptance.2026-08"), org.mockito.ArgumentMatchers.eq("LEARNING_SANDBOX_COURSE_PUBLISH:sandbox-course:v1"), org.mockito.ArgumentMatchers.eq("sandbox-catalog-publish-001"), org.mockito.ArgumentMatchers.contains("LEARNING_SANDBOX_COURSE_REVISION_OR_AUTHORITY_CONFLICT"));
    }

    @Test
    void nonExistingDraftWithNonzeroExpectedRevisionFailsWithoutAnInsert() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        LearningCourseUpsertRequest request = new LearningCourseUpsertRequest("沙箱课程", "Sandbox course", "正文", "Body", "Basics", "Article", "Beginner", BigDecimal.TEN, "5 min", "draft", "acceptance", "verify", java.util.List.of(), null, null, null, null, 1L);

        assertThat(service.saveDraft("acceptance.2026-08", "sandbox-course", request, "sandbox-catalog-save-002").getCode()).isEqualTo(409);
        verify(mapper, org.mockito.Mockito.never()).insertSandboxCourse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void competingSecondVersionPublishIsRejectedByTheSinglePublishedAuthority() {
        ReflectionTestUtils.setField(service, "configuredRunId", "acceptance.2026-08");
        when(mapper.claimSandboxCatalogIdempotency(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(mapper.publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v1", 0)).thenReturn(1);
        when(mapper.publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v2", 0)).thenReturn(0);

        assertThat(service.publish("acceptance.2026-08", "sandbox-course", "v1", 0, "sandbox-catalog-publish-v1").getCode()).isZero();
        assertThat(service.publish("acceptance.2026-08", "sandbox-course", "v2", 0, "sandbox-catalog-publish-v2").getCode()).isEqualTo(409);
        verify(mapper).publishSandboxCourse("acceptance.2026-08", "sandbox-course", "v2", 0);
    }
}
