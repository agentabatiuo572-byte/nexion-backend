package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportPersonalPhraseMapper;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quick replies visible only to the advisor who saved them; no review because nothing is shared. */
@Service
@RequiredArgsConstructor
public class SupportPersonalPhraseService {
    static final int MAX_PHRASES = 100;
    static final int MAX_LENGTH = 500;
    private final SupportPersonalPhraseMapper mapper;
    private final SupportOwnershipService ownership;

    @PostConstruct
    void initialize() {
        mapper.ensureTable();
    }

    public List<Map<String, Object>> list() {
        return mapper.list(actor());
    }

    @Transactional
    public List<Map<String, Object>> add(String content) {
        Long actor = actor();
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > MAX_LENGTH) throw new BizException(422, "SUPPORT_PHRASE_LENGTH_INVALID");
        if (mapper.count(actor) >= MAX_PHRASES) throw new BizException(409, "SUPPORT_PHRASE_LIMIT_REACHED");
        mapper.insert(actor, text);
        return mapper.list(actor);
    }

    @Transactional
    public List<Map<String, Object>> delete(Long id) {
        Long actor = actor();
        if (id == null || mapper.delete(actor, id) != 1) throw new BizException(404, "SUPPORT_PHRASE_NOT_FOUND");
        return mapper.list(actor);
    }

    private Long actor() {
        Long actor = ownership.actorId();
        if (actor == null) throw new BizException(401, "UNAUTHORIZED");
        return actor;
    }
}
