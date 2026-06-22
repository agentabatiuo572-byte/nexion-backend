package ffdd.opsconsole.device.domain;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import java.time.LocalDateTime;
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

    Optional<DeviceTaskView> updateTaskPrice(String taskId, java.math.BigDecimal price, LocalDateTime now);

    Optional<DeviceTaskView> updateTaskStatus(String taskId, String status, LocalDateTime now);

    boolean softDeleteTask(String taskId, LocalDateTime now);

    PageResult<DeviceOrderView> pageOrders(DeviceOrderQueryRequest request);

    Optional<DeviceOrderView> findOrder(String orderNo);

    Optional<DeviceOrderView> updateOrderState(String orderNo, String state, LocalDateTime now);
}
