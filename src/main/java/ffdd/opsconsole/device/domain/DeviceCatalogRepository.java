package ffdd.opsconsole.device.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceCatalogRepository {
    PageResult<DeviceSkuView> pageSkus(DeviceSkuQueryRequest request);

    Optional<DeviceSkuView> findSku(String skuId);

    DeviceSkuView createSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now);

    Optional<DeviceSkuView> updateSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now);

    Optional<DeviceSkuView> updateSkuStatus(String skuId, String status, LocalDateTime now);

    boolean softDeleteSku(String skuId, LocalDateTime now);

    PageResult<DeviceReviewView> pageReviews(DeviceReviewQueryRequest request);

    Optional<DeviceReviewView> findReview(String reviewId);

    DeviceReviewView createReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now);

    Optional<DeviceReviewView> updateReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now);

    Optional<DeviceReviewView> updateReviewStatus(String reviewId, String status, LocalDateTime now);

    boolean softDeleteReview(String reviewId, LocalDateTime now);

    PageResult<DeviceTaskView> pageTasks(DeviceTaskQueryRequest request);

    Optional<DeviceTaskView> findTask(String taskId);

    DeviceTaskView createTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now);

    Optional<DeviceTaskView> updateTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now);

    Optional<DeviceTaskView> updateTaskPrice(String taskId, java.math.BigDecimal price, LocalDateTime now);

    Optional<DeviceTaskView> updateTaskStatus(String taskId, String status, LocalDateTime now);

    boolean softDeleteTask(String taskId, LocalDateTime now);

    PageResult<DeviceOrderView> pageOrders(DeviceOrderQueryRequest request);

    Optional<DeviceOrderView> findOrder(String orderNo);

    Optional<DeviceOrderView> updateOrderState(String orderNo, String state, LocalDateTime now);

    List<DevicePhaseView> listPhases(String scope, boolean includeArchived);

    Optional<DevicePhaseView> findPhase(String scope, String phaseId);

    Optional<DevicePhaseView> findPhaseByLabel(String scope, String label);

    DevicePhaseView savePhase(
            String scope,
            String currentPhaseId,
            String label,
            String meta,
            String skus,
            Integer sortOrder,
            String status,
            LocalDateTime now);

    boolean archivePhase(String scope, String phaseId, LocalDateTime now);

    void backfillPhaseReferences(String scope, LocalDateTime now);

    int countSkusByUnlockPhase(String phaseId);

    int countGenerationGatesByPhase(String phaseId);

    List<DeviceGenerationGateView> listGenerationGates(boolean includeArchived);

    Optional<DeviceGenerationGateView> findGenerationGate(String skuId);

    DeviceGenerationGateView saveGenerationGate(
            String skuId,
            String name,
            Integer releaseMonth,
            String phase,
            BigDecimal discount,
            Boolean eligibility,
            Integer phaseOffset,
            Boolean forceUnlock,
            String status,
            LocalDateTime now);

    boolean archiveGenerationGate(String skuId, LocalDateTime now);
}
