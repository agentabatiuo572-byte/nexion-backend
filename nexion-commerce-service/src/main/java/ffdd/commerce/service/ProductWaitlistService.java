package ffdd.commerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.commerce.domain.Product;
import ffdd.commerce.domain.ProductWaitlist;
import ffdd.commerce.dto.ProductWaitlistQueryRequest;
import ffdd.commerce.mapper.ProductMapper;
import ffdd.commerce.mapper.ProductWaitlistMapper;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProductWaitlistService {
    private static final String ACTIVE = "ACTIVE";
    private static final String CANCELLED = "CANCELLED";

    private final ProductMapper productMapper;
    private final ProductWaitlistMapper waitlistMapper;

    public ProductWaitlistService(ProductMapper productMapper, ProductWaitlistMapper waitlistMapper) {
        this.productMapper = productMapper;
        this.waitlistMapper = waitlistMapper;
    }

    public ProductWaitlist joinProductWaitlist(Long productId, Long userId) {
        if (userId == null) {
            throw new BizException("User id is required");
        }
        Product product = requireProduct(productId);
        ProductWaitlist existing = findActive(product.getId(), userId);
        if (existing != null) {
            return existing;
        }
        ProductWaitlist row = new ProductWaitlist();
        row.setProductId(product.getId());
        row.setProductNo(product.getProductNo());
        row.setProductName(product.getName());
        row.setUserId(userId);
        row.setUnlockPhase(product.getUnlockPhase());
        row.setStatus(ACTIVE);
        row.setIsDeleted(0);
        try {
            waitlistMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            ProductWaitlist duplicate = findActive(product.getId(), userId);
            if (duplicate != null) {
                return duplicate;
            }
            throw ignored;
        }
        return row;
    }

    public PageResult<ProductWaitlist> pageWaitlist(ProductWaitlistQueryRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());
        LambdaQueryWrapper<ProductWaitlist> wrapper = new LambdaQueryWrapper<ProductWaitlist>()
                .eq(ProductWaitlist::getIsDeleted, 0)
                .eq(request.getProductId() != null, ProductWaitlist::getProductId, request.getProductId())
                .eq(request.getUserId() != null, ProductWaitlist::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), ProductWaitlist::getStatus, normalizeStatus(request.getStatus()))
                .orderByDesc(ProductWaitlist::getCreatedAt);
        Page<ProductWaitlist> page = waitlistMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public void deleteWaitlist(Long id) {
        ProductWaitlist row = waitlistMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) {
            throw new BizException("Product waitlist row not found");
        }
        row.setStatus(CANCELLED);
        row.setIsDeleted(1);
        waitlistMapper.updateById(row);
    }

    private Product requireProduct(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BizException("Product id is required");
        }
        Product product = productMapper.selectById(productId);
        if (product == null || Integer.valueOf(1).equals(product.getIsDeleted())) {
            throw new BizException("Product not found");
        }
        return product;
    }

    private ProductWaitlist findActive(Long productId, Long userId) {
        return waitlistMapper.selectOne(new LambdaQueryWrapper<ProductWaitlist>()
                .eq(ProductWaitlist::getProductId, productId)
                .eq(ProductWaitlist::getUserId, userId)
                .eq(ProductWaitlist::getStatus, ACTIVE)
                .eq(ProductWaitlist::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private String normalizeStatus(String status) {
        return status == null ? null : status.trim().toUpperCase();
    }

    private long normalizePageNum(Long pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
