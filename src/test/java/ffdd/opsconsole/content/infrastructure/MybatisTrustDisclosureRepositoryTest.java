package ffdd.opsconsole.content.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.DisclosureAckStatusMapper;
import ffdd.opsconsole.content.mapper.DisclosureChapterMapper;
import ffdd.opsconsole.content.mapper.DisclosureDraftMapper;
import ffdd.opsconsole.content.mapper.DisclosureGateActionMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionMapper;
import ffdd.opsconsole.content.mapper.TrustSectionFieldMapper;
import ffdd.opsconsole.content.mapper.TrustSectionMapper;
import ffdd.opsconsole.content.mapper.TrustSectionVersionMapper;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MybatisTrustDisclosureRepositoryTest {
    @Mock private TrustSectionMapper trustSectionMapper;
    @Mock private TrustSectionFieldMapper trustSectionFieldMapper;
    @Mock private TrustSectionVersionMapper trustSectionVersionMapper;
    @Mock private DisclosureJurisdictionMapper disclosureJurisdictionMapper;
    @Mock private DisclosureChapterMapper disclosureChapterMapper;
    @Mock private DisclosureGateActionMapper disclosureGateActionMapper;
    @Mock private DisclosureDraftMapper disclosureDraftMapper;
    @Mock private DisclosureAckStatusMapper disclosureAckStatusMapper;

    private MybatisTrustDisclosureRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MybatisTrustDisclosureRepository(
                trustSectionMapper, trustSectionFieldMapper, trustSectionVersionMapper,
                disclosureJurisdictionMapper, disclosureChapterMapper, disclosureGateActionMapper,
                disclosureDraftMapper, disclosureAckStatusMapper);
    }

    @Test
    void deletingRecreatedDraftPreservesHistoryWithUniqueTombstoneVersion() {
        TrustSectionVersionEntity active = version(22L, 0);
        when(trustSectionVersionMapper.selectOne(any())).thenReturn(active);

        repository.deleteTrustSectionDraft("leadership", "v6", LocalDateTime.now());

        verify(trustSectionVersionMapper).updateById(active);
        org.assertj.core.api.Assertions.assertThat(active.getVersionLabel()).isEqualTo("v6~d~22");
        org.assertj.core.api.Assertions.assertThat(active.getIsDeleted()).isOne();
    }

    @Test
    void replacingPublishedFieldsReusesExistingUniqueRows() {
        TrustSectionFieldEntity existing = new TrustSectionFieldEntity();
        existing.setId(31L);
        existing.setSectionKey("leadership");
        existing.setFieldKey("summary.zh");
        existing.setFieldValue("旧内容");
        existing.setIsDeleted(1);
        when(trustSectionFieldMapper.selectList(any())).thenReturn(List.of(existing));

        repository.replaceSectionFields("leadership", List.of(
                new TrustSectionFieldInput("summary.zh", "中文摘要", "新内容")),
                "Marina K.", LocalDateTime.now());

        verify(trustSectionFieldMapper).updateById(existing);
        verify(trustSectionFieldMapper, never()).insert(any(TrustSectionFieldEntity.class));
        org.assertj.core.api.Assertions.assertThat(existing.getFieldValue()).isEqualTo("新内容");
        org.assertj.core.api.Assertions.assertThat(existing.getIsDeleted()).isZero();
    }

    private static TrustSectionVersionEntity version(long id, int deleted) {
        TrustSectionVersionEntity entity = new TrustSectionVersionEntity();
        entity.setId(id);
        entity.setSectionKey("leadership");
        entity.setVersionLabel("v6");
        entity.setIsDeleted(deleted);
        return entity;
    }
}
