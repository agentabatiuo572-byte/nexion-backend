package ffdd.opsconsole.platform.application;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.domain.OpsDomainCatalog;
import ffdd.opsconsole.platform.dto.AdminOption;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
import ffdd.opsconsole.risk.domain.RiskScoringSourceCatalog;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsOptionsService {
    private final OpsOptionsMapper optionsMapper;
    private final AdminRolePermissionMapper permissionMapper;

    public ApiResult<List<AdminOption>> options(String domain, String name) {
        String normalizedDomain = normalize(domain);
        String normalizedName = normalize(name);
        if (!StringUtils.hasText(normalizedDomain) || !StringUtils.hasText(normalizedName)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPTION_NAME_REQUIRED");
        }
        return switch (normalizedName) {
            case "domains", "domain" -> ApiResult.ok(domainOptions());
            case "roles", "role" -> ApiResult.ok(roleOptions());
            case "permissions", "permission" -> ApiResult.ok(permissionOptions());
            case "user-status" -> ApiResult.ok(options("正常", "ACTIVE", "冻结", "FROZEN", "注销", "CLOSED"));
            case "kyc-status" -> ApiResult.ok(options("未认证", "NONE", "待审核", "PENDING", "已通过", "APPROVED", "已拒绝", "REJECTED"));
            case "kyc-network", "kyc-networks" -> ApiResult.ok(options("TRC20", "TRC20", "ERC20", "ERC20", "BTC", "BTC", "ETH", "ETH", "BSC", "BSC", "SOL", "SOL", "Polygon", "POLYGON"));
            case "asset-adjustment-asset", "asset-adjustment-assets" -> ApiResult.ok(options("USDT", "USDT", "NEX", "NEX"));
            case "asset-adjustment-direction", "asset-adjustment-directions" -> ApiResult.ok(options("增加", "CREDIT", "扣减", "DEBIT"));
            case "asset-adjustment-reason", "asset-adjustment-reasons" -> ApiResult.ok(options("客服补偿", "CUSTOMER_COMPENSATION", "系统纠错", "SYSTEM_CORRECTION", "活动补发", "CAMPAIGN_REISSUE", "争议退回", "DISPUTE_REVERSAL"));
            case "asset-adjustment-status", "asset-adjustment-statuses" -> ApiResult.ok(options("待复核", "PENDING_REVIEW", "已通过", "APPROVED", "已驳回", "REJECTED", "红线挂起", "SUSPENDED"));
            case "withdrawal-status" -> ApiResult.ok(options("审核中", "REVIEWING", "延迟复核", "DELAYED", "已冻结", "FROZEN", "待上链", "PENDING_CHAIN", "上链中", "CHAIN_SUBMITTED", "成功", "SUCCESS", "失败", "FAILED", "已拒绝", "REJECTED"));
            case "risk-action" -> ApiResult.ok(options("放行", "ALLOW", "人工审核", "REVIEW", "冻结", "FREEZE", "拒绝", "REJECT"));
            case "scoring-source", "scoring-sources" -> ApiResult.ok(RiskScoringSourceCatalog.values().stream()
                    .map(value -> AdminOption.of(value, value, Map.of("ownerDomain", "K4")))
                    .toList());
            case "report-type" -> ApiResult.ok(options("经营日报", "OPS_DAILY", "财务对账", "FINANCE_RECON", "风控报表", "RISK", "监管导出", "REGULATORY"));
            case "report-status", "report-statuses" -> ApiResult.ok(reportStatusOptions());
            case "media-usage" -> ApiResult.ok(options("商品主图", "sku-image", "商品视频", "sku-video", "内容封面", "content-cover", "公告附件", "notice-attachment"));
            case "exchange-queue-mode", "exchange-queue-modes" -> ApiResult.ok(options("排队", "QUEUE", "拒绝", "REJECT"));
            case "exchange-param", "exchange-params" -> ApiResult.ok(options("单用户日额度", "userDailyCap", "平台日额度", "platformDailyCap", "兑换手续费率", "fee", "最低手续费", "feeMin", "超 cap 处置策略", "queueMode"));
            case "exchange-gate", "exchange-gates" -> ApiResult.ok(options("需实名", "kyc", "单用户超限", "user", "平台超限", "platform", "地域封锁", "geo"));
            case "device-status" -> ApiResult.ok(options("空闲", "OFFLINE", "运行中", "ACTIVE", "暂停", "SUSPENDED", "回收站", "RECYCLED"));
            case "datacenters", "datacenter" -> ApiResult.ok(datacenterOptions());
            case "sku-tier", "sku-tiers" -> ApiResult.ok(options("Entry", "Entry", "Pro", "Pro", "Flagship", "Flagship", "Share", "Share"));
            case "sku-generation", "sku-generations" -> ApiResult.ok(options("Gen 1", "1", "Gen 2", "2", "Gen 3", "3"));
            case "sku-lifecycle", "sku-lifecycles" -> ApiResult.ok(options("active(在产)", "active", "legacy(停代)", "legacy"));
            case "sku-unlock-phase", "sku-unlock-phases" -> ApiResult.ok(options("P1(立即开放)", "P1", "P2(门控)", "P2", "P3(门控)", "P3", "P4(门控)", "P4", "P5(门控)", "P5", "P6(门控)", "P6"));
            case "task-unit", "task-units" -> ApiResult.ok(options("按任务", "/job", "按 1k tokens", "/1k", "按分钟", "/min"));
            case "task-requirement", "task-requirements" -> ApiResult.ok(options("S1+", "S1+", "需 NexionBox Pro", "需 NexionBox Pro", "需 NexionRack", "需 NexionRack"));
            case "order-terminal-state", "order-terminal-states" -> ApiResult.ok(options("支付失败", "payment_failed", "订单过期", "expired", "已退款", "refunded", "开通失败", "provisioning_failed"));
            case "review-rating", "review-ratings" -> ApiResult.ok(options("5 星", "5", "4 星", "4", "3 星", "3", "2 星", "2", "1 星", "1"));
            case "review-status", "review-statuses" -> ApiResult.ok(options("展示", "published", "隐藏", "hidden"));
            case "transfer-targets" -> ApiResult.ok(optionRows(optionsMapper::transferTargets));
            case "support-agents" -> ApiResult.ok(optionRows(optionsMapper::supportAgents));
            case "support-ticket-category", "support-ticket-categories" -> ApiResult.ok(options("账号问题", "account", "提现问题", "withdrawal", "充值问题", "deposit", "KYC 认证", "kyc", "硬件设备", "hardware", "收益问题", "earnings", "Genesis 权益", "genesis", "技术问题", "technical", "其他", "other"));
            case "support-ticket-status", "support-ticket-statuses" -> ApiResult.ok(options("待处理", "OPEN", "处理中", "IN_PROGRESS", "待用户回复", "PENDING_USER", "已解决", "RESOLVED", "已关闭", "CLOSED"));
            case "support-ticket-priority", "support-ticket-priorities" -> ApiResult.ok(options("低", "LOW", "普通", "NORMAL", "高", "HIGH", "紧急", "URGENT"));
            case "support-faq-category", "support-faq-categories" -> ApiResult.ok(options("通用", "general", "账号问题", "account", "提现问题", "withdrawal", "充值问题", "deposit", "KYC 认证", "kyc", "硬件设备", "hardware", "收益问题", "earnings", "Genesis 权益", "genesis", "技术问题", "technical", "其他", "other"));
            case "support-faq-surface", "support-faq-surfaces" -> ApiResult.ok(options("帮助中心", "Help Center", "创建工单页", "Ticket Create", "Nova AI", "Nova"));
            case "support-faq-status", "support-faq-statuses" -> ApiResult.ok(options("已发布", "PUBLISHED", "草稿", "DRAFT"));
            case "support-sla-queue", "support-sla-queues" -> ApiResult.ok(optionRows(optionsMapper::supportSlaQueues));
            case "support-sla-escalation", "support-sla-escalations" -> ApiResult.ok(optionRows(optionsMapper::supportSlaEscalations));
            case "support-reply-template", "support-reply-templates" -> ApiResult.ok(optionRows(optionsMapper::sessionReplyTemplates));
            case "support-sla", "support-sla-categories" -> ApiResult.ok(supportSlaOptions());
            case "nova-channel-status", "nova-channel-statuses" -> ApiResult.ok(options("开启", "true", "停推", "false"));
            case "nova-template-status", "nova-template-statuses" -> ApiResult.ok(options("草稿", "DRAFT", "已发布", "PUBLISHED", "已归档", "ARCHIVED"));
            case "nova-template-cta", "nova-template-ctas" -> ApiResult.ok(options(
                    "无跳转", "NONE",
                    "每周回顾", "/me/weekly",
                    "设备商城", "/devices",
                    "质押产品", "/staking",
                    "团队中心", "/team",
                    "收益任务", "/earn",
                    "客服中心", "/support"));
            case "nova-social-event", "nova-social-events" -> ApiResult.ok(options("提现到账", "withdrawal", "V 等级晋升", "vrank", "Genesis 成交", "genesis", "AI 客户消费", "aiClient", "每小时新增用户", "newUsers"));
            case "nova-social-pool", "nova-social-pools" -> ApiResult.ok(options("人名池", "SOCIAL_NAMES", "城市池", "CITIES", "AI 客户池", "AI_CLIENTS"));
            case "sku-status" -> ApiResult.ok(options("草稿", "DRAFT", "在售", "ON_SALE", "售罄", "SOLD_OUT", "下架", "OFFLINE"));
            case "region", "regions" -> ApiResult.ok(options("中国香港", "HK", "新加坡", "SG", "美国", "US", "日本", "JP"));
            default -> ApiResult.fail(404, "OPTION_NOT_FOUND");
        };
    }

    private List<AdminOption> domainOptions() {
        return OpsDomainCatalog.activeDomains().stream()
                .map(domain -> AdminOption.of(domain.code().displayName(), domain.code().name(), Map.of(
                        "adminResource", domain.code().adminResource(),
                        "apiPrefix", domain.adminApiPrefix())))
                .toList();
    }

    private List<AdminOption> roleOptions() {
        List<AdminOption> databaseRoles = queryDistinctOptions(() -> optionsMapper.selectList(
                        new LambdaQueryWrapper<AdminRoleOptionEntity>()
                                .select(AdminRoleOptionEntity::getRoleName)
                                .eq(AdminRoleOptionEntity::getStatus, 1)
                                .orderByAsc(AdminRoleOptionEntity::getRoleName)
                                .last("LIMIT 100"))
                .stream()
                .map(AdminRoleOptionEntity::getRoleName)
                .toList());
        if (!databaseRoles.isEmpty()) {
            return databaseRoles;
        }
        return options(
                "超级管理员", "SUPER_ADMIN",
                "配置运营", "CONFIG_ADMIN",
                "财务", "FINANCE",
                "风控", "RISK",
                "内容运营", "CONTENT",
                "增长运营", "GROWTH",
                "客服", "SUPPORT",
                "只读审计", "AUDITOR");
    }

    private List<AdminOption> permissionOptions() {
        // 经典 RBAC：权限下拉从 DB 动态读（265 细点），数据驱动；#9 A8 权限字典按域/页面分组优化
        return permissionMapper.selectAllPermissionOptions().stream()
                .map(row -> AdminOption.of(row.permissionName(), row.permissionCode()))
                .toList();
    }

    private List<AdminOption> datacenterOptions() {
        return queryDistinctOptions(optionsMapper::datacenters);
    }

    private List<AdminOption> supportSlaOptions() {
        try {
            return optionsMapper.supportSlaRules().stream()
                    .filter(row -> row != null && StringUtils.hasText(row.category()))
                    .map(row -> AdminOption.of(row.category().trim(), row.category().trim(), Map.of(
                            "firstResponseMins", row.firstResponseMins() == null ? 0 : row.firstResponseMins(),
                            "resolutionHours", row.resolutionHours() == null ? 0 : row.resolutionHours(),
                            "queue", safeString(row.queue()),
                            "escalation", safeString(row.escalation()))))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<AdminOption> reportStatusOptions() {
        return List.of(
                AdminOption.of("全部", "ALL", Map.of("statuses", List.of())),
                AdminOption.of("待确认", "PENDING_CONFIRM,PENDING_SPLIT_CONFIRM", Map.of("statuses", List.of("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM"))),
                AdminOption.of("生成中", "GENERATING", Map.of("statuses", List.of("GENERATING"))),
                AdminOption.of("可下载", "READY", Map.of("statuses", List.of("READY"))));
    }

    private List<AdminOption> queryDistinctOptions(OptionSupplier supplier) {
        try {
            Set<String> values = new LinkedHashSet<>(supplier.get());
            return values.stream()
                    .filter(StringUtils::hasText)
                    .map(value -> AdminOption.of(value.trim(), value.trim()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<AdminOption> optionRows(OptionRowSupplier supplier) {
        try {
            return supplier.get().stream()
                    .filter(row -> row != null && StringUtils.hasText(row.label()) && StringUtils.hasText(row.value()))
                    .map(row -> AdminOption.of(row.label().trim(), row.value().trim()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private String safeString(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private List<AdminOption> options(String... labelValuePairs) {
        List<AdminOption> options = new ArrayList<>();
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            options.add(AdminOption.of(labelValuePairs[i], labelValuePairs[i + 1]));
        }
        return List.copyOf(options);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("_", "-");
    }

    @FunctionalInterface
    private interface OptionSupplier {
        List<String> get();
    }

    @FunctionalInterface
    private interface OptionRowSupplier {
        List<OpsOptionsMapper.OptionRow> get();
    }
}
