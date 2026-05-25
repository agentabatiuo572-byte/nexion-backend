package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.I18nMessage;
import ffdd.system.dto.I18nBatchQueryRequest;
import ffdd.system.dto.I18nMessageCreateRequest;
import ffdd.system.dto.I18nMessageResponse;
import ffdd.system.dto.I18nMessageUpdateRequest;
import ffdd.system.mapper.I18nMessageMapper;
import ffdd.system.service.SystemI18nService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemI18nServiceImpl implements SystemI18nService {
    private static final int MAX_BATCH_SIZE = 100;

    private final I18nMessageMapper i18nMessageMapper;

    @Override
    public List<I18nMessageResponse> list(String locale, String query, Integer status, int limit) {
        String normalizedLocale = locale == null ? null : SystemFieldValidator.normalizeLocale(locale);
        String normalizedQuery = SystemFieldValidator.trimToNull(query);
        LambdaQueryWrapper<I18nMessage> wrapper = new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getIsDeleted, 0)
                .eq(status != null, I18nMessage::getStatus, status)
                .eq(StringUtils.hasText(normalizedLocale), I18nMessage::getLocale, normalizedLocale)
                .and(StringUtils.hasText(normalizedQuery), nested -> nested
                        .like(I18nMessage::getMessageKey, normalizedQuery)
                        .or()
                        .like(I18nMessage::getMessageValue, normalizedQuery))
                .orderByDesc(I18nMessage::getUpdatedAt)
                .last("LIMIT " + SystemFieldValidator.normalizeLimit(limit));
        return i18nMessageMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public I18nMessageResponse getActive(String messageKey, String locale) {
        String normalizedKey = normalizeMessageKey(messageKey);
        String normalizedLocale = SystemFieldValidator.normalizeLocale(locale);
        I18nMessage message = i18nMessageMapper.selectOne(new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getMessageKey, normalizedKey)
                .eq(I18nMessage::getLocale, normalizedLocale)
                .eq(I18nMessage::getStatus, SystemFieldValidator.ACTIVE)
                .eq(I18nMessage::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (message == null) {
            throw new BizException(404, "I18n message not found");
        }
        return toResponse(message);
    }

    @Override
    public List<I18nMessageResponse> batchGetActive(I18nBatchQueryRequest request) {
        if (request == null) {
            throw new BizException("I18n batch query request is required");
        }
        String normalizedLocale = SystemFieldValidator.normalizeLocale(request.getLocale());
        List<String> normalizedKeys = normalizeBatchKeys(request.getMessageKeys());
        List<I18nMessage> messages = i18nMessageMapper.selectList(new LambdaQueryWrapper<I18nMessage>()
                .in(I18nMessage::getMessageKey, normalizedKeys)
                .eq(I18nMessage::getLocale, normalizedLocale)
                .eq(I18nMessage::getStatus, SystemFieldValidator.ACTIVE)
                .eq(I18nMessage::getIsDeleted, 0));
        Map<String, I18nMessage> byKey = messages.stream()
                .collect(Collectors.toMap(I18nMessage::getMessageKey, message -> message, (left, right) -> left));
        return normalizedKeys.stream()
                .map(byKey::get)
                .filter(message -> message != null)
                .map(this::toResponse)
                .toList();
    }

    @Override
    public I18nMessageResponse create(I18nMessageCreateRequest request) {
        if (request == null) {
            throw new BizException("I18n message request is required");
        }
        String normalizedKey = normalizeMessageKey(request.getMessageKey());
        String normalizedLocale = SystemFieldValidator.normalizeLocale(request.getLocale());
        I18nMessage existing = i18nMessageMapper.selectOne(new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getMessageKey, normalizedKey)
                .eq(I18nMessage::getLocale, normalizedLocale)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BizException(409, "I18n message already exists");
        }

        I18nMessage message = new I18nMessage();
        message.setMessageKey(normalizedKey);
        message.setLocale(normalizedLocale);
        message.setMessageValue(SystemFieldValidator.requireText(request.getMessageValue(), "messageValue", 1024));
        message.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        message.setIsDeleted(0);
        i18nMessageMapper.insert(message);
        return toResponse(message);
    }

    @Override
    public I18nMessageResponse update(Long id, I18nMessageUpdateRequest request) {
        if (id == null) {
            throw new BizException("I18n message id is required");
        }
        if (request == null) {
            throw new BizException("I18n message request is required");
        }
        I18nMessage message = i18nMessageMapper.selectById(id);
        if (message == null || Integer.valueOf(1).equals(message.getIsDeleted())) {
            throw new BizException(404, "I18n message not found");
        }
        if (request.getMessageValue() != null) {
            message.setMessageValue(SystemFieldValidator.requireText(request.getMessageValue(), "messageValue", 1024));
        }
        if (request.getStatus() != null) {
            message.setStatus(SystemFieldValidator.normalizeStatus(request.getStatus()));
        }
        i18nMessageMapper.updateById(message);
        return toResponse(message);
    }

    private List<String> normalizeBatchKeys(List<String> messageKeys) {
        if (messageKeys == null || messageKeys.isEmpty()) {
            throw new BizException("messageKeys is required");
        }
        if (messageKeys.size() > MAX_BATCH_SIZE) {
            throw new BizException("messageKeys size must be <= " + MAX_BATCH_SIZE);
        }
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (String messageKey : messageKeys) {
            ordered.put(normalizeMessageKey(messageKey), Boolean.TRUE);
        }
        return List.copyOf(ordered.keySet());
    }

    private String normalizeMessageKey(String messageKey) {
        return SystemFieldValidator.normalizeCode(messageKey, "messageKey", 128);
    }

    private I18nMessageResponse toResponse(I18nMessage message) {
        return new I18nMessageResponse(
                message.getId(),
                message.getMessageKey(),
                message.getLocale(),
                message.getMessageValue(),
                message.getStatus(),
                message.getCreatedAt(),
                message.getUpdatedAt());
    }
}
