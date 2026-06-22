package ffdd.opsconsole.platform.application;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.domain.OpsDomainCatalog;
import ffdd.opsconsole.platform.dto.AdminOption;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
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
            case "transfer-targets" -> ApiResult.ok(options("一线客服", "agent-tier-1", "二线客服", "agent-tier-2", "风控专员", "risk-agent"));
            case "support-agents" -> ApiResult.ok(options("一线客服", "1001", "二线客服", "1002", "风控专员", "1003"));
            case "support-ticket-category", "support-ticket-categories" -> ApiResult.ok(options("账号问题", "account", "提现问题", "withdrawal", "充值问题", "deposit", "KYC 认证", "kyc", "硬件设备", "hardware", "收益问题", "earnings", "Genesis 权益", "genesis", "技术问题", "technical", "其他", "other"));
            case "support-ticket-status", "support-ticket-statuses" -> ApiResult.ok(options("待处理", "OPEN", "处理中", "IN_PROGRESS", "待用户回复", "PENDING_USER", "已解决", "RESOLVED", "已关闭", "CLOSED"));
            case "support-ticket-priority", "support-ticket-priorities" -> ApiResult.ok(options("低", "LOW", "普通", "NORMAL", "高", "HIGH", "紧急", "URGENT"));
            case "support-faq-category", "support-faq-categories" -> ApiResult.ok(options("通用", "general", "账号问题", "account", "提现问题", "withdrawal", "充值问题", "deposit", "KYC 认证", "kyc", "硬件设备", "hardware", "收益问题", "earnings", "Genesis 权益", "genesis", "技术问题", "technical", "其他", "other"));
            case "support-faq-surface", "support-faq-surfaces" -> ApiResult.ok(options("帮助中心", "Help Center", "创建工单页", "Ticket Create", "Nova AI", "Nova"));
            case "support-faq-status", "support-faq-statuses" -> ApiResult.ok(options("已发布", "PUBLISHED", "草稿", "DRAFT"));
            case "support-sla-queue", "support-sla-queues" -> ApiResult.ok(options("支付台", "支付台", "充值台", "充值台", "合规台", "合规台", "设备运维台", "设备运维台", "账户台", "账户台", "收益台", "收益台", "创世节点台", "创世节点台", "技术支持台", "技术支持台", "一线客服", "一线客服"));
            case "support-sla-escalation", "support-sla-escalations" -> ApiResult.ok(options("D2 withdrawal review", "D2 withdrawal review", "D1 deposit reconciliation", "D1 deposit reconciliation", "C4 KYC ledger", "C4 KYC ledger", "E5 device ops", "E5 device ops", "C5 security", "C5 security", "F2 earnings ledger", "F2 earnings ledger", "G4 Genesis economy", "G4 Genesis economy", "A3 system config", "A3 system config", "M2 support lead", "M2 support lead"));
            case "support-reply-template", "support-reply-templates" -> ApiResult.ok(options(
                    "核对后台状态", "已确认工单信息，我会先核对后台状态并在本线程同步处理进度。",
                    "链上确认队列", "支付台账已核对，当前卡在链上确认队列；我会保留人工复核并继续跟进。",
                    "补充材料", "请补充截图、设备 LED pattern 或交易 hash，这样我们能把工单转给对应队列。",
                    "处理完成", "问题已处理并关闭；如果状态未更新，回复本工单会自动重新打开。"));
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
        return options("超级管理员", "SUPER_ADMIN", "运营管理员", "OPS_ADMIN", "财务审核员", "FINANCE_REVIEWER", "客服主管", "SUPPORT_LEAD", "风控专员", "RISK_ANALYST");
    }

    private List<AdminOption> permissionOptions() {
        return options(
                "系统读取", "PERM_SYSTEM_READ",
                "系统写入", "PERM_SYSTEM_WRITE",
                "审计读取", "PERM_AUDIT_READ",
                "用户读取", "PERM_USER_READ",
                "用户写入", "PERM_USER_WRITE",
                "提现读取", "PERM_WITHDRAWAL_READ",
                "提现审核", "PERM_WITHDRAWAL_REVIEW",
                "设备读取", "PERM_DEVICE_READ",
                "设备写入", "PERM_DEVICE_WRITE",
                "内容读取", "PERM_CONTENT_READ",
                "内容写入", "PERM_CONTENT_WRITE");
    }

    private List<AdminOption> datacenterOptions() {
        List<AdminOption> databaseDcs = queryDistinctOptions(optionsMapper::datacenters);
        if (!databaseDcs.isEmpty()) {
            return databaseDcs;
        }
        return options("未分配", "UNASSIGNED", "香港 1 区", "HK-1", "新加坡 1 区", "SG-1", "美国 1 区", "US-1");
    }

    private List<AdminOption> supportSlaOptions() {
        return List.of(
                AdminOption.of("提现", "withdrawal", Map.of("firstResponseMins", 15, "resolutionHours", 12, "queue", "支付台")),
                AdminOption.of("充值", "deposit", Map.of("firstResponseMins", 20, "resolutionHours", 12, "queue", "充值台")),
                AdminOption.of("实名", "kyc", Map.of("firstResponseMins", 30, "resolutionHours", 24, "queue", "合规台")),
                AdminOption.of("硬件", "hardware", Map.of("firstResponseMins", 45, "resolutionHours", 48, "queue", "设备运维台")),
                AdminOption.of("节点", "genesis", Map.of("firstResponseMins", 20, "resolutionHours", 18, "queue", "创世节点台")),
                AdminOption.of("账户", "account", Map.of("firstResponseMins", 30, "resolutionHours", 24, "queue", "账户台")),
                AdminOption.of("收益", "earnings", Map.of("firstResponseMins", 45, "resolutionHours", 48, "queue", "收益台")),
                AdminOption.of("技术", "technical", Map.of("firstResponseMins", 60, "resolutionHours", 72, "queue", "技术支持台")),
                AdminOption.of("其他", "other", Map.of("firstResponseMins", 60, "resolutionHours", 72, "queue", "一线客服")));
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
}
