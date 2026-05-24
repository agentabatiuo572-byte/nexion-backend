package ffdd.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.openapi.domain.OpenApiNonce;
import ffdd.openapi.mapper.OpenApiNonceMapper;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OpenApiNonceService {
    private final OpenApiNonceMapper nonceMapper;
    private final long nonceTtlSeconds;

    public OpenApiNonceService(
            OpenApiNonceMapper nonceMapper,
            @Value("${nexion.openapi.nonce-ttl-seconds:300}") long nonceTtlSeconds) {
        this.nonceMapper = nonceMapper;
        this.nonceTtlSeconds = Math.max(60, nonceTtlSeconds);
    }

    public void claim(String appKey, String nonce) {
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(nonce)) {
            throw new BizException(401, "Missing OpenAPI nonce");
        }
        LocalDateTime now = LocalDateTime.now();
        purgeExpired(appKey, nonce, now);
        OpenApiNonce record = new OpenApiNonce();
        record.setAppKey(appKey);
        record.setNonce(nonce);
        record.setExpiresAt(now.plusSeconds(nonceTtlSeconds));
        record.setIsDeleted(0);
        try {
            nonceMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "OpenAPI nonce already used");
        }
    }

    private void purgeExpired(String appKey, String nonce, LocalDateTime now) {
        nonceMapper.delete(new LambdaQueryWrapper<OpenApiNonce>()
                .eq(OpenApiNonce::getAppKey, appKey)
                .eq(OpenApiNonce::getNonce, nonce)
                .le(OpenApiNonce::getExpiresAt, now));
    }
}
