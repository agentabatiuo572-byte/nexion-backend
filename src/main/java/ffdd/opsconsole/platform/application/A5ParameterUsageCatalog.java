package ffdd.opsconsole.platform.application;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A5 usage notes backed by a Java consumer. Absence is not evidence of retirement. */
final class A5ParameterUsageCatalog {
    private A5ParameterUsageCatalog() {}

    record Usage(String description, String evidence) {}

    private static final Pattern MONTH = Pattern.compile("growth\\.phase\\.month\\.[1-9][0-9]*\\.([A-Za-z]+)");
    private static final Pattern AGENT = Pattern.compile("content\\.support\\.load\\.agent\\.[0-9]+\\.(busy|cap)");
    private static final Pattern FORECAST_SNAPSHOT = Pattern.compile("treasury\\.d3\\.forecast-config\\.snapshot\\.[1-9][0-9]*");
    private static final Map<String, Usage> MONTH_FIELDS = Map.ofEntries(
            Map.entry("newUserBonusMultiplier", new Usage("选中运营月的新人礼倍率，单位为倍。", "GrowthRhythmSnapshot; OpsReferralRewardService")),
            Map.entry("inviteRewardMultiplier", new Usage("选中运营月的邀请奖励倍率，单位为倍。", "GrowthRhythmSnapshot; OpsReferralRewardService")),
            Map.entry("reinvestMultiplier", new Usage("选中运营月的复投倍率，单位为倍；不等同于利息收益率。", "GrowthRhythmSnapshot; AppRepurchaseService")),
            Map.entry("questBonusMultiplier", new Usage("选中运营月的任务奖励倍率，任务快照已有冻结倍率时沿用快照。", "GrowthRhythmSnapshot; AppGrowthEngagementService")),
            Map.entry("binaryDailyCap", new Usage("选中运营月的双轨日奖励上限，单位 USDT；周期上限按天数折算。", "GrowthRhythmSnapshot; BinarySettlementPolicyProvider; AppBinaryProjectionService")),
            Map.entry("withdrawCooldownDays", new Usage("选中运营月的提现冷却天数；提现评估和持有时限读取该值。", "GrowthRhythmSnapshot; AppWithdrawalService")),
            Map.entry("complianceHoldEnabled", new Usage("选中运营月的提现合规审查开关，供提现合规策略读取。", "GrowthRhythmSnapshot; WithdrawalCompliancePolicy")),
            Map.entry("complianceReviewThresholdUsdt", new Usage("选中运营月的新链上提现合规等待金额线，严格超过才等。", "GrowthRhythmSnapshot; WithdrawalCompliancePolicy")),
            Map.entry("withdrawPenaltyFeeRate", new Usage("已退役：月份提现惩罚费率保留兼容读取和校验，当前提现不收取惩罚费。", "OpsPhaseOverviewService; AppWithdrawalService")));

    private static final Map<String, Usage> EXACT = """
            admin.a2.reason_min_chars|审批操作理由的最少字符数；A2 写入校验读取。|A2RuntimePolicy
            admin.a2.retention_months|审批审计记录保留期限，单位月；由 A2 保留期策略读取。|A2RuntimePolicy
            admin.a2.schema_version|审批审计事件采用的受支持数据结构版本，不是自由文本。|A2RuntimePolicy
            feature.ops.maintenanceBanner|未确认：目录名称为维护横幅，尚未核实实际展示消费者。|OpsPlatformParamRegistryService
            platform.global_rate_limit_per_minute|全局请求限流的每分钟阈值；由请求过滤器读取。|PlatformGlobalRateLimitFilter
            admin.a4.event.kpi.day0|注册到首笔收益的 Day0 指标时间阈值。|A4RuntimePolicyService; OpsEventCenterService
            admin.a4.event.kpi.retention|事件中心显示的留存统计窗口定义；目录说明不代表控制报表计算。|OpsEventCenterService
            wallet.dual-ledger.healthy-pct|储备覆盖率的健康线配置，单位百分比。|OpsTreasuryService
            wallet.dual-ledger.redline-pct|储备覆盖率的红线配置，单位百分比。|OpsTreasuryService
            wallet.dual-ledger.run-risk-pct|资金视图的挤兑风险比例设置；以资金视图的计算口径为准。|OpsTreasuryService
            auth.risk.c6.version|注册与登录风险配置的版本号，用于识别编辑时读到的配置。|RegistrationRiskCaptchaWindow; OpsUserService
            auth.risk.captcha_off_window|注册风控验证码临时停用窗口及恢复时间；不等同于停用全部认证。|RegistrationRiskCaptchaWindow
            auth.risk.otp_cooldown_seconds|验证码再次发送的冷却时间，单位秒。|AppOtpPolicy; UserSecurityRiskFacadeAdapter
            auth.risk.otp_max_24h|验证码在滚动 24 小时内的发送次数上限。|AppOtpPolicy; AppUserRegistrationService
            auth.risk.otp_ttl_minutes|验证码有效期限，单位分钟。|AppOtpPolicy
            auth.session.access_ttl_hours|应用访问令牌的配置期限，单位小时。|PlatformConfigFacade; AppUserAuthService
            auth.session.refresh_ttl_days|应用刷新凭证的配置期限，单位天。|PlatformConfigFacade; AppUserAuthService
            auth.session.idle_ttl_days|应用会话允许的空闲期限，单位天。|AppSingleSessionService; CurrentAppSessionService
            finance.topup.card.cardRetryLimit|仅管理统计：用于财务卡失败风险分类阈值，不是当前在线充值重试拦截。|OpsFinanceService
            finance.topup.psp.primary|仅管理展示：主支付服务商名称，不表示 App 实际走该通道。|OpsFinanceService
            treasury.d3.forecast-config|D3 当前预测模型配置。|OpsTreasuryService
            treasury.d3.forecast-config.active-version|D3 当前生效的预测配置版本。|OpsTreasuryService
            treasury.d3.forecast-config.pending|D3 等待生效的预测模型配置。|OpsTreasuryService
            treasury.d3.forecast-config.pending-effective-at|D3 待生效预测配置的时间点。|OpsTreasuryService
            treasury.d3.forecast-config.pending-version|D3 待生效的预测配置版本。|OpsTreasuryService
            treasury.d3.forecast-config.version|D3 预测配置修改的版本号。|OpsTreasuryService
            withdrawal.bep20.enabled|BEP20 提现通道开关；提现提交按网络检查。|AppWithdrawalService
            withdrawal.erc20.enabled|ERC20 提现通道开关；提现提交按网络检查。|AppWithdrawalService
            withdrawal.trc20.enabled|TRC20 提现通道开关；提现提交按网络检查。|AppWithdrawalService
            withdrawal.d5.version|D5 提现配置版本号；报价和提交使用服务端的策略版本。|OpsFinanceService; AppWithdrawalService
            withdrawal.daily_count_limit|用户每日提现次数上限。|AppWithdrawalService
            withdrawal.max_balance_pct|单次提现允许取用可用余额的最大比例。|AppWithdrawalService
            withdrawal.network_confirm_fee_usd.bep20|BEP20 提现固定网络费用的配置额，单位 USDT。|AppWithdrawalService
            withdrawal.network_confirm_fee_usd.erc20|ERC20 提现固定网络费用的配置额，单位 USDT。|AppWithdrawalService
            withdrawal.network_confirm_fee_usd.trc20|TRC20 提现固定网络费用的配置额，单位 USDT。|AppWithdrawalService
            withdrawal.nex_fee_offset_rate|提现策略读取的 UVT 手续费抵扣配置值。|AppWithdrawalService
            withdrawal.small_amount_threshold_usd|判定提现是否满足小额条件的金额阈值，单位 USDT。|AppWithdrawalService
            withdrawal.payout_sla_hours|提现处理时限，单位小时；由持有截止时间加该时长计算最迟处理时刻。|AppWithdrawalService
            finance.payout_vnd.values|越南盾提现配置的字段集合，按 D7 配置服务解析。|PayoutVndConfigService
            finance.payout_vnd.version|越南盾提现配置版本，供 D7 读取和更新校验。|PayoutVndConfigService
            store.bundle.discount.2.rate|组合购达到两件时使用的折扣配置（比例）。|BundleDiscountPolicy
            store.bundle.discount.3.rate|组合购达到三件时使用的折扣配置（比例）。|BundleDiscountPolicy
            store.bundle.discount.4plus.rate|组合购达到四件及以上时使用的折扣配置（比例）。|BundleDiscountPolicy
            store.bundle.discount.version|组合购折扣配置版本。|BundleDiscountPolicy
            E.compute.phoneCalibration.policy|手机算力政策的当前、待生效版本和修订记录，由手机校准配置服务读取。|PhoneCalibrationConfigService
            dailyUsdtPerBaseline|按基准算力折算每日 USDT 收益估算的配置。|ComputeConfigRegistry; GrowthPublicStatsService
            team.direct-referral.policy-version|直推奖励政策版本；用于识别已发布的直推奖励配置。|DirectReferralPolicyService
            team.rank_how.published|已发布的团队等级说明快照。|PublishedRankHowPolicyService
            team.seven-layer.revision|七层团队奖励配置修订号。|DirectReferralPolicyService
            team.ui.F.influence.clampMax|七层团队佣金影响力因子的上限。|UnilevelCommissionService
            team.ui.F.influence.clampMin|七层团队佣金影响力因子的下限。|UnilevelCommissionService
            team.ui.F.leaderboard.paused|排行榜奖励的暂停状态，供奖励与客户端读取。|LeadershipPoolService; AppTeamInsightsService
            team.ui.F.leaderboard.poolUsd|排行榜奖金池配置金额，单位 USDT。|LeadershipPoolService; AppTeamInsightsService
            team.ui.F.promo.weekMultiplier|七层佣金使用的周推广倍率。|UnilevelCommissionService
            team.ui.F.unilevel.depthGate|深层推荐佣金的资格检查起始层级。|CommissionGuideRuleService; UnilevelCommissionService
            team.ui.F.binary.gvResetCron|双轨业绩清零排程配置表达式，供双轨策略读取。|BinarySettlementPolicyProvider; AppBinaryProjectionService
            team.ui.F.pool.configVersion|团队激励池配置版本，用于变更校验。|LeadershipPoolConfigGuard
            team.ui.F.pool.monthlyCap|团队激励池每月奖励上限配置。|LeadershipPoolConfigGuard
            team.ui.F.pool.ratio|团队激励池分配比例配置。|LeadershipPoolConfigGuard
            team.ui.F.pool.settleCron|团队激励池结算排程配置；仅有该值不代表有执行回执。|LeadershipPoolConfigGuard
            team.ui.F.pool.unlockVRank|团队激励池资格所需的团队等级。|LeadershipPoolConfigGuard
            wallet.exchange.nex_usdt_price|兑换侧读取的 UVT 对 USDT 价格，单位 USDT/UVT；不保证价格可用。|AppExchangeService
            wallet.nex_market.weekly_curve|UVT 周计划价格曲线，报价侧读取有效帧。|AppExchangeService; OpsNexMarketService
            G.genesis.lottery.monthlyCapacity|创世抽奖月度名额配置。|AppRepurchaseService; OpsRepurchaseAdminService
            H1.rhythm.currentMonth|当前运营月份，用于选择当月生效配置。|GrowthRhythmSnapshot
            H1.rhythm.phaseProgressPct|运营阶段进度，单位百分比；供阶段读取展示。|GrowthRhythmSnapshot
            H1.rhythm.totalMonths|运营周期总月数；阶段快照校验当前月份是否在范围内。|GrowthRhythmSnapshot
            growth.phase.current|用于业务事件归属的运营阶段标识。|BehaviorAnalyticsMapper; AppOrderCommandMapper
            growth.phase.current_month|运营月份的兼容配置键；按有效运营阶段配置读取。|GrowthRhythmSnapshot; OpsDeviceService
            growth.phase.genesis_emissions_open|创世收益释放开关；创世读写检查此状态。|AppGenesisService; G4AdminCommandService
            growth.phase.withdraw_penalty_fee_rate|已退役：提现惩罚费率保留兼容配置，当前提现不收取该费。|OpsPhaseOverviewService; AppWithdrawalService
            growth.withdraw_nex_gate.hold_days|提现资产门槛中持有天数配置，单位天。|OpsGrowthService
            growth.withdraw_nex_gate.min_balance_nex|提现资产门槛中最低 UVT 余额配置。|OpsGrowthService
            growth.quest.day_one.tri_reward|新手任务奖励档位配置；生成任务快照时读取，领取按冻结规则判断。|DayOneInstanceFacadeAdapter; AppGrowthEngagementService
            K.rewards.inviterReward.nexAmount|邀请人奖励的 UVT 基础额，结算还会应用有效阶段倍率。|OpsReferralRewardService
            K.rewards.referral.effectiveAt|当前新人礼和邀请奖励的生效时间；候选登记时间按该窗口筛选。|OpsReferralRewardService; ReferralRewardMapper
            K.rewards.referral.enabled|新人礼与邀请奖励的启用状态。|OpsReferralRewardService
            K.rewards.referral.version|新人礼与邀请奖励配置版本，修改与审批核对该版本。|OpsReferralRewardService
            K.rewards.welcomeGift.nexAmount|新人礼 UVT 基础额。|OpsReferralRewardService
            K.rewards.welcomeGift.usdtAmount|新人礼 USDT 基础额。|OpsReferralRewardService
            growth.public_stats.values|首页展示统计的配置内容，不代表实际账本结算。|GrowthPublicStatsService
            growth.public_stats.version|首页展示统计配置版本。|GrowthPublicStatsService
            disclosure.gate.staking|质押相关业务的风险披露确认入口配置。|AppRepurchaseService; OpsGrowthService
            risk.k1.release.version|多账户释放规则的配置版本标识，供已发布促销关联版本时核对。|PromotionNativeContractResolver
            content.support.load.version|客服负载管理配置版本，保存时用于检查旧配置。|OpsSupportLoadConfigService
            content.support.load.defaultCap|客服负载管理视图的默认值。自动分配读取客服档案的同时接待上限，空值表示不限制。|OpsSupportLoadConfigService; SupportGroupMapper
            content.support.load.burstCap|客服负载管理视图保留的突发上限。自动分配读取客服档案的同时接待上限，空值表示不限制。|OpsSupportLoadConfigService; SupportGroupMapper
            content.support.load.warnPct|仅管理读取：负载告警比例配置，单位百分比；未据此认定有告警发送。|OpsSupportLoadConfigService
            content.support.load.autoBalance|仅管理读取：客服负载自动均衡开关配置；未据此认定所有分配流程自动均衡。|OpsSupportLoadConfigService
            content.support.load.quietHourBalance|仅管理读取：安静时段均衡选项；未确认任务调度消费者。|OpsSupportLoadConfigService
            content.support.load.overflowQueue|仅管理读取：超限转入队列的规则选项；未确认所有分配路径执行。|OpsSupportLoadConfigService
            I.session.advisor.policy.audience|仅管理读取：顾问自动触达的目标受众配置。|OpsSessionTemplateService
            I.session.advisor.policy.cooldownHours|仅管理读取：顾问自动触达的冷却小时数配置。|OpsSessionTemplateService
            I.session.advisor.policy.delayMs|仅管理读取：顾问自动触达的延迟配置，单位毫秒。|OpsSessionTemplateService
            I.session.advisor.policy.maxPerSession|仅管理读取：每次会话的顾问自动触达次数配置。|OpsSessionTemplateService
            I.session.workbench.timeoutFallback|仅管理读取：会话工作台的超时回退开关。|OpsSessionTemplateService
            I.session.cat.advisor.enabled|顾问会话类别启用开关。|ConversationCategoryPolicy; OpsConversationService
            I.session.cat.support.enabled|客服会话类别启用开关。|ConversationCategoryPolicy
            wallet.nex_market.oracle|已退役：G3 独立价格数据源配置禁止新写，不据该值改变报价。|OpsNexMarketService
            withdrawal.fee_rate|仅管理兼容：提现通用网络费用比例配置；链上提交实际按配置的网络固定确认费读取。|OpsFinanceService; AppWithdrawalService
            withdrawal.fee_min_usdt|仅管理兼容：提现通用最低网络费配置，单位 USDT；不冒充当前链上提交的固定确认费。|OpsFinanceService; AppWithdrawalService
            withdrawal.fee_max_usdt|仅管理兼容：提现通用最高网络费配置，单位 USDT；不冒充当前链上提交的固定确认费。|OpsFinanceService; AppWithdrawalService
            withdrawal.min_usdt|D5 管理的链上最低提现金额，适用于 TRC20、BEP20、ERC20 的报价与新单提交，单位 USDT。|OpsFinanceService; AppWithdrawalService
            E.task.queueSaturation|任务计价计算读取的队列饱和度系数；管理端在计算任务价格时应用该系数。|OpsDeviceService
            team.ui.F.vrank.permanent|团队等级的保持开关；晋升引擎读取是否不降级。|VRankPromotionEngine
            wallet.nex_market.control.active_day_index|已经生效的行情曲线日序号，用于读取当前帧，不是日期。|OpsNexMarketService
            wallet.nex_market.control.loop|行情曲线到末帧后的循环选项。|OpsNexMarketService
            wallet.nex_market.control.pin|行情曲线的钉住控制值；按服务端钉住规则读取。|OpsNexMarketService
            wallet.nex_market.control.schedule|行情曲线的排程控制值，与已生效的曲线帧分开。|OpsNexMarketService
            wallet.nex_market.pump_probability|内部执行状态：写入当前行情帧的概率值；本键不直接决定报价。|OpsNexMarketService
            wallet.nex_market.volatility_pct|内部执行状态：兼容保留的当前行情帧波动值；独立波动覆盖写入口已退役。|OpsNexMarketService
            risk.k1.release.releaseMode|内部执行状态：当前释放只支持人工审核，历史模式值不能恢复自动可信证明释放。|RiskReleaseParamsService
            risk.k1.release.duplicateAccountPendingFrom|重复账户进入待审核分档的数量起点。|RiskReleaseParamsService
            risk.k1.release.duplicateAccountFreezeFrom|重复账户进入冻结分档的数量起点。|RiskReleaseParamsService
            growth.earn_milestone.tick_interval_seconds|仅管理读取：收益里程碑刷新间隔配置，单位秒；此处不承诺实际调度频率。|OpsGrowthService
            team.runtime.F.leaderboard.lastSettled.today|内部执行状态：日榜最近结算检查点，不是运营参数。|LeadershipPoolService
            team.runtime.F.leaderboard.lastSettled.week|内部执行状态：周榜最近结算检查点，不是运营参数。|LeadershipPoolService
            team.runtime.F.leaderboard.lastSettled.month|内部执行状态：月榜最近结算检查点，不是运营参数。|LeadershipPoolService
            """.lines().map(line -> line.split("\\|", 3))
            .collect(Collectors.toUnmodifiableMap(row -> row[0], row -> new Usage(row[1], row[2])));

    static Usage usage(String key) {
        Usage known = EXACT.get(key);
        if (known != null) return known;
        var month = MONTH.matcher(key);
        if (month.matches()) return MONTH_FIELDS.get(month.group(1));
        var agent = AGENT.matcher(key);
        if (agent.matches()) {
            String meaning = "busy".equals(agent.group(1))
                    ? "未确认：管理负载视图的忙碌状态取自当前坐席可用性；未核实此旧键另有生效消费者。"
                    : "客服负载管理视图保留的坐席上限。自动分配读取客服档案的同时接待上限，空值表示不限制。";
            return new Usage(meaning, "OpsSupportLoadConfigService");
        }
        if (key.startsWith("finance.topup.channel.card.") || key.startsWith("finance.topup.card.")
                && !"finance.topup.card.cardRetryLimit".equals(key)) {
            return new Usage("已退役：旧银行卡充值通道已关闭，这些参数不能恢复通道。", "TopupCardLifecycleService; OpsFinanceService");
        }
        if (key.matches("finance[.]topup[.]channel[.](bep20|erc20|trc20|vietqr)[.](fee|fee_unit|min_amount|min_amount_unit|enabled)")) {
            String meaning = switch (key.substring(key.lastIndexOf('.') + 1)) {
                case "fee" -> "手续费配置额";
                case "fee_unit" -> "手续费配置的单位";
                case "min_amount" -> "最低充值配置额";
                case "min_amount_unit" -> "最低充值配置的单位";
                default -> "充值通道启用配置";
            };
            return new Usage("仅管理读取：" + meaning + "；不据该视图判断实际通道路由或费用。", "OpsFinanceService");
        }
        if (FORECAST_SNAPSHOT.matcher(key).matches()) {
            return new Usage("D3 预测配置的历史版本快照，不是当前生效配置。", "OpsTreasuryService");
        }
        return null;
    }
}
