package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.infrastructure.DisclosureChapterEntity;
import ffdd.opsconsole.content.infrastructure.DisclosureDraftEntity;
import ffdd.opsconsole.content.infrastructure.DisclosureJurisdictionCatalogEntity;
import ffdd.opsconsole.content.infrastructure.DisclosureJurisdictionEntity;
import ffdd.opsconsole.content.mapper.DisclosureChapterMapper;
import ffdd.opsconsole.content.mapper.DisclosureDraftMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionCatalogMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable fixture for the one explicitly isolated local-sandbox profile. The
 * default/production profiles do not instantiate this bean and never receive
 * this disclosure or its mock provenance.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class RiskDisclosureLocalSandboxInitializer implements ApplicationRunner {
    private static final String CODE = "LOCAL-SANDBOX";
    private static final String VERSION = "v-local-1";
    private static final String OPERATOR = "local-sandbox:risk-disclosure-fixture";

    private final Environment environment;
    private final DisclosureJurisdictionCatalogMapper catalogMapper;
    private final DisclosureJurisdictionMapper jurisdictionMapper;
    private final DisclosureDraftMapper draftMapper;
    private final DisclosureChapterMapper chapterMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!isStrictLocalSandbox()) return;
        LocalDateTime now = LocalDateTime.now();
        ensureCatalog(now);
        ensureJurisdiction(now);
        ensureDraft(now);
        ensureChapters(now);
    }

    private boolean isStrictLocalSandbox() {
        String[] active = environment == null ? new String[0] : environment.getActiveProfiles();
        return active.length == 1 && "dev".equals(active[0]);
    }

    private void ensureCatalog(LocalDateTime now) {
        DisclosureJurisdictionCatalogEntity row = catalogMapper.selectOne(new LambdaQueryWrapper<DisclosureJurisdictionCatalogEntity>()
                .eq(DisclosureJurisdictionCatalogEntity::getJurisdictionCode, CODE).last("LIMIT 1"));
        if (row == null) {
            row = new DisclosureJurisdictionCatalogEntity();
            row.setJurisdictionCode(CODE);
            row.setCreatedAt(now);
        }
        row.setJurisdictionName("本地沙箱风险披露");
        row.setStatus("ACTIVE");
        row.setRevision(row.getRevision() == null ? 1L : row.getRevision());
        row.setLastOperator(OPERATOR);
        row.setUpdatedAt(now);
        row.setIsDeleted(0);
        if (row.getId() == null) catalogMapper.insert(row); else catalogMapper.updateById(row);
    }

    private void ensureJurisdiction(LocalDateTime now) {
        DisclosureJurisdictionEntity row = jurisdictionMapper.selectOne(new LambdaQueryWrapper<DisclosureJurisdictionEntity>()
                .eq(DisclosureJurisdictionEntity::getJurisdictionCode, CODE).last("LIMIT 1"));
        if (row == null) {
            row = new DisclosureJurisdictionEntity();
            row.setJurisdictionCode(CODE);
            row.setCreatedAt(now);
        }
        row.setJurisdictionName("本地沙箱风险披露");
        row.setCountryCodes(CODE);
        row.setVersionLabel(VERSION);
        row.setStatus("PUBLISHED");
        row.setPublishedAtLabel(LocalDate.now().toString());
        row.setAffectedCount(0L);
        row.setAckProgressPct(BigDecimal.ZERO);
        row.setBlockedCount(0L);
        row.setLastOperator(OPERATOR);
        row.setUpdatedAt(now);
        row.setIsDeleted(0);
        if (row.getId() == null) jurisdictionMapper.insert(row); else jurisdictionMapper.updateById(row);
    }

    private void ensureDraft(LocalDateTime now) {
        DisclosureDraftEntity row = draftMapper.selectOne(new LambdaQueryWrapper<DisclosureDraftEntity>()
                .eq(DisclosureDraftEntity::getJurisdictionCode, CODE)
                .eq(DisclosureDraftEntity::getVersionLabel, VERSION).last("LIMIT 1"));
        if (row == null) {
            row = new DisclosureDraftEntity();
            row.setJurisdictionCode(CODE);
            row.setVersionLabel(VERSION);
            row.setRevision(1L);
            row.setCreatedAt(now);
        }
        row.setLanguageScope("zh+vi+en");
        row.setEffectiveDate(LocalDate.now().toString());
        row.setRequiresReack(true);
        row.setZhBody("本地沙箱演示风险披露，不代表真实金融产品、收益或服务承诺。");
        row.setViBody("Đây là công bố rủi ro trình diễn sandbox cục bộ, không đại diện cho sản phẩm hoặc cam kết tài chính thực tế.");
        row.setEnBody("Local sandbox demonstration disclosure; it is not a real financial product, return, or service commitment.");
        row.setStatus("PUBLISHED");
        row.setContentHash("local-sandbox-fixture");
        row.setLastOperator(OPERATOR);
        row.setUpdatedAt(now);
        row.setIsDeleted(0);
        if (row.getId() == null) draftMapper.insert(row); else draftMapper.updateById(row);
    }

    private void ensureChapters(LocalDateTime now) {
        List<String> titles = List.of(
                "演示性质", "收益不构成承诺", "市场与流动性风险", "提现与合规审查",
                "锁定与不可撤销", "账户与数据安全", "适用范围与免责声明");
        for (int i = 0; i < titles.size(); i++) {
            String no = String.format("%02d", i + 1);
            DisclosureChapterEntity row = chapterMapper.selectOne(new LambdaQueryWrapper<DisclosureChapterEntity>()
                    .eq(DisclosureChapterEntity::getJurisdictionCode, CODE)
                    .eq(DisclosureChapterEntity::getVersionLabel, VERSION)
                    .eq(DisclosureChapterEntity::getChapterNo, no).last("LIMIT 1"));
            if (row == null) {
                row = new DisclosureChapterEntity();
                row.setJurisdictionCode(CODE);
                row.setVersionLabel(VERSION);
                row.setChapterNo(no);
                row.setCreatedAt(now);
            }
            String title = titles.get(i);
            row.setZhTitle(title);
            row.setViTitle("Công bố rủi ro sandbox cục bộ");
            row.setEnTitle("Local sandbox risk disclosure");
            row.setZhBody(title + "：本内容仅用于本地沙箱演示，不能据此作出真实资金或收益决策。");
            row.setViBody("Nội dung này chỉ dùng cho trình diễn sandbox cục bộ và không phải cơ sở cho quyết định tài chính thực tế.");
            row.setEnBody("This content is for local sandbox demonstration only and must not guide real-fund decisions.");
            row.setSortOrder(i + 1);
            row.setLastOperator(OPERATOR);
            row.setUpdatedAt(now);
            row.setIsDeleted(0);
            if (row.getId() == null) chapterMapper.insert(row); else chapterMapper.updateById(row);
        }
    }
}
