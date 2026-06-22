package ffdd.opsconsole.device.infrastructure;


import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import ffdd.opsconsole.shared.api.PageResult;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisDeviceCatalogRepository implements DeviceCatalogRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DeviceCatalogMapper mapper;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createSkuTable();
        mapper.createReviewTable();
        mapper.createTaskTable();
        mapper.createOrderTable();
    }

    @Override
    public PageResult<DeviceSkuView> pageSkus(DeviceSkuQueryRequest request) {
        String status = request == null ? null : normalizeLower(request.status());
        String keyword = request == null ? null : normalize(request.keyword());
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countSkus(status, keyword);
        List<DeviceSkuView> records = mapper.pageSkus(status, keyword, pageSize, offset).stream()
                .map(this::skuView)
                .toList();
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<DeviceSkuView> findSku(String skuId) {
        return Optional.ofNullable(mapper.findSku(skuId)).map(this::skuView);
    }

    @Override
    public DeviceSkuView createSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
        mapper.insertSku(skuWrite(skuId, request, now, now));
        return findSku(skuId).orElseThrow();
    }

    @Override
    public Optional<DeviceSkuView> updateSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
        int updated = mapper.updateSku(skuWrite(skuId, request, null, now));
        return updated == 0 ? Optional.empty() : findSku(skuId);
    }

    @Override
    public Optional<DeviceSkuView> updateSkuStatus(String skuId, String status, LocalDateTime now) {
        int updated = mapper.updateSkuStatus(skuId, status, now);
        return updated == 0 ? Optional.empty() : findSku(skuId);
    }

    @Override
    public boolean softDeleteSku(String skuId, LocalDateTime now) {
        return mapper.softDeleteSku(skuId, now) > 0;
    }

    @Override
    public PageResult<DeviceReviewView> pageReviews(DeviceReviewQueryRequest request) {
        String skuId = request == null ? null : normalize(request.skuId());
        String status = request == null ? null : normalizeLower(request.status());
        Integer rating = request == null ? null : request.rating();
        String keyword = request == null ? null : normalize(request.keyword());
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countReviews(skuId, status, rating, keyword);
        List<DeviceReviewView> records = mapper.pageReviews(skuId, status, rating, keyword, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<DeviceReviewView> findReview(String reviewId) {
        return Optional.ofNullable(mapper.findReview(reviewId));
    }

    @Override
    public DeviceReviewView createReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
        mapper.insertReview(reviewWrite(reviewId, request, now, now));
        return findReview(reviewId).orElseThrow();
    }

    @Override
    public Optional<DeviceReviewView> updateReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
        int updated = mapper.updateReview(reviewWrite(reviewId, request, null, now));
        return updated == 0 ? Optional.empty() : findReview(reviewId);
    }

    @Override
    public Optional<DeviceReviewView> updateReviewStatus(String reviewId, String status, LocalDateTime now) {
        int updated = mapper.updateReviewStatus(reviewId, status, now);
        return updated == 0 ? Optional.empty() : findReview(reviewId);
    }

    @Override
    public boolean softDeleteReview(String reviewId, LocalDateTime now) {
        return mapper.softDeleteReview(reviewId, now) > 0;
    }

    @Override
    public PageResult<DeviceTaskView> pageTasks(DeviceTaskQueryRequest request) {
        String status = request == null ? null : normalizeLower(request.status());
        String keyword = request == null ? null : normalize(request.keyword());
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countTasks(status, keyword);
        List<DeviceTaskView> records = mapper.pageTasks(status, keyword, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<DeviceTaskView> findTask(String taskId) {
        return Optional.ofNullable(mapper.findTask(taskId));
    }

    @Override
    public DeviceTaskView createTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now) {
        mapper.insertTask(taskWrite(taskId, request, now, now));
        return findTask(taskId).orElseThrow();
    }

    @Override
    public Optional<DeviceTaskView> updateTaskPrice(String taskId, BigDecimal price, LocalDateTime now) {
        int updated = mapper.updateTaskPrice(taskId, valueOrZero(price), now);
        return updated == 0 ? Optional.empty() : findTask(taskId);
    }

    @Override
    public Optional<DeviceTaskView> updateTaskStatus(String taskId, String status, LocalDateTime now) {
        int updated = mapper.updateTaskStatus(taskId, taskStatus(status), now);
        return updated == 0 ? Optional.empty() : findTask(taskId);
    }

    @Override
    public boolean softDeleteTask(String taskId, LocalDateTime now) {
        return mapper.softDeleteTask(taskId, now) > 0;
    }

    @Override
    public PageResult<DeviceOrderView> pageOrders(DeviceOrderQueryRequest request) {
        String state = request == null ? null : normalizeLower(request.state());
        String keyword = request == null ? null : normalize(request.keyword());
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;
        long total = mapper.countOrders(state, keyword);
        List<DeviceOrderView> records = mapper.pageOrders(state, keyword, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<DeviceOrderView> findOrder(String orderNo) {
        return Optional.ofNullable(mapper.findOrder(orderNo));
    }

    @Override
    public Optional<DeviceOrderView> updateOrderState(String orderNo, String state, LocalDateTime now) {
        int updated = mapper.updateOrderState(orderNo, state, now);
        return updated == 0 ? Optional.empty() : findOrder(orderNo);
    }

    private DeviceSkuView skuView(DeviceCatalogMapper.SkuRow row) {
        return new DeviceSkuView(
                row.skuId(),
                row.name(),
                row.tier(),
                row.tagline(),
                row.badge(),
                row.gpu(),
                row.vram(),
                row.hashRate(),
                row.power(),
                row.datacenter(),
                row.price(),
                row.dailyEarn(),
                row.dailyEarnNex(),
                row.shareYieldMin(),
                row.shareYieldMax(),
                row.baseRate(),
                row.sold(),
                row.stock(),
                row.rating(),
                row.reviews(),
                row.aiImageGenPerMin(),
                row.aiLlmTokensPerSec(),
                row.aiVideoMinPerHour(),
                row.aiFineTuneMins(),
                row.aiUnlocks(),
                features(row.featuresJson()),
                row.generation(),
                row.lifecycle(),
                row.supersededBy(),
                row.tradeinDiscount(),
                row.unlockPhase(),
                row.imageAssetId(),
                row.imageObjectKey(),
                row.imagePreviewUrl(),
                row.tag(),
                row.status(),
                row.createdAt(),
                row.updatedAt());
    }

    private DeviceCatalogMapper.SkuWrite skuWrite(String skuId, DeviceSkuUpsertRequest request,
                                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new DeviceCatalogMapper.SkuWrite(
                skuId,
                request.name().trim(),
                blankToNull(request.tier()),
                blankToNull(request.tagline()),
                blankToNull(request.badge()),
                blankToNull(request.gpu()),
                blankToNull(request.vram()),
                blankToNull(request.hashRate()),
                blankToNull(request.power()),
                blankToNull(request.datacenter()),
                valueOrZero(request.price()),
                valueOrZero(request.dailyEarn()),
                valueOrZero(request.dailyEarnNex()),
                request.shareYieldMin(),
                request.shareYieldMax(),
                blankToNull(request.baseRate()),
                request.sold(),
                StringUtils.hasText(request.stock()) ? request.stock().trim() : "0",
                request.rating(),
                request.reviews(),
                request.aiImageGenPerMin(),
                request.aiLlmTokensPerSec(),
                request.aiVideoMinPerHour(),
                request.aiFineTuneMins(),
                blankToNull(request.aiUnlocks()),
                featuresJson(request.features()),
                request.generation(),
                blankToNull(request.lifecycle()),
                blankToNull(request.supersededBy()),
                request.tradeinDiscount(),
                StringUtils.hasText(request.unlockPhase()) ? request.unlockPhase().trim().toUpperCase() : "P1",
                blankToNull(request.imageAssetId()),
                blankToNull(request.imageObjectKey()),
                blankToNull(request.imagePreviewUrl()),
                blankToNull(request.tag()),
                skuStatus(request.status()),
                createdAt,
                updatedAt);
    }

    private DeviceCatalogMapper.ReviewWrite reviewWrite(String reviewId, DeviceReviewUpsertRequest request,
                                                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new DeviceCatalogMapper.ReviewWrite(
                reviewId,
                request.skuId().trim(),
                request.author().trim(),
                request.rating(),
                request.content().trim(),
                dateText(request.dateText()),
                reviewStatus(request.status()),
                createdAt,
                updatedAt);
    }

    private DeviceCatalogMapper.TaskWrite taskWrite(String taskId, DeviceTaskUpsertRequest request,
                                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new DeviceCatalogMapper.TaskWrite(
                taskId,
                request.name().trim(),
                valueOrZero(request.price()),
                StringUtils.hasText(request.unit()) ? request.unit().trim() : "/job",
                StringUtils.hasText(request.requirement()) ? request.requirement().trim() : "S1+",
                valueOrZero(request.saturation()),
                taskStatus(request.status()),
                createdAt,
                updatedAt);
    }

    private List<String> features(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String featuresJson(List<String> features) {
        try {
            return objectMapper.writeValueAsString(features == null ? List.of() : features);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String skuStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase() : "pending";
    }

    private String reviewStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase() : "published";
    }

    private String taskStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase() : "active";
    }

    private String dateText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "刚刚";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : null;
    }

    private long normalizePage(Long pageNum) {
        return Math.max(1L, pageNum == null ? 1L : pageNum);
    }

    private long normalizeSize(Long pageSize) {
        return Math.max(1L, Math.min(100L, pageSize == null ? 20L : pageSize));
    }
}
