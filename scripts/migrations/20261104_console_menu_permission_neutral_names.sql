-- PC console menu and permission names: use the App product names (团队奖励设置 / 团队平衡奖励 /
-- 奖励记录核对 / 锁定计划 …) instead of 佣金 / 双轨 / 版税 / 复投 / 分红 / APY / 赎回 wording.
-- Display names only; permission_code and menu_code are unchanged. Rerunnable: each row is
-- renamed only while its name is byte-identical to the shipped seed name (BINARY compare; the
-- column collation ignores case and punctuation width), so an operator rename is kept. The
-- classic RBAC seed now writes the new names; 20261028_f5_commission_key_split.sql still writes
-- the old F5 names on replay, and this file runs after it in the startup chain.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_menu SET menu_name='团队奖励设置', updated_at=NOW() WHERE menu_code='F2' AND CAST(menu_name AS BINARY)=CAST('网络版税费率' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='团队奖励设置', updated_at=NOW() WHERE menu_code='F2' AND CAST(menu_name_zh AS BINARY)=CAST('网络版税费率' AS BINARY);
UPDATE nx_admin_menu SET menu_name='团队平衡奖励', updated_at=NOW() WHERE menu_code='F3' AND CAST(menu_name AS BINARY)=CAST('双轨结算引擎' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='团队平衡奖励', updated_at=NOW() WHERE menu_code='F3' AND CAST(menu_name_zh AS BINARY)=CAST('双轨结算引擎' AS BINARY);
UPDATE nx_admin_menu SET menu_name='奖励池、名额与排行榜', updated_at=NOW() WHERE menu_code='F4' AND CAST(menu_name AS BINARY)=CAST('池 / 配额 / 大使 / 榜' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='奖励池、名额与排行榜', updated_at=NOW() WHERE menu_code='F4' AND CAST(menu_name_zh AS BINARY)=CAST('池 / 配额 / 大使 / 榜' AS BINARY);
UPDATE nx_admin_menu SET menu_name='奖励记录核对', updated_at=NOW() WHERE menu_code='F5' AND CAST(menu_name AS BINARY)=CAST('佣金事件审计' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='奖励记录核对', updated_at=NOW() WHERE menu_code='F5' AND CAST(menu_name_zh AS BINARY)=CAST('佣金事件审计' AS BINARY);
UPDATE nx_admin_menu SET menu_name='质押产品配置', updated_at=NOW() WHERE menu_code='G1' AND CAST(menu_name AS BINARY)=CAST('Staking 池配置' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='质押产品配置', updated_at=NOW() WHERE menu_code='G1' AND CAST(menu_name_zh AS BINARY)=CAST('Staking 池配置' AS BINARY);
UPDATE nx_admin_menu SET menu_name='创世节点销售与收益', updated_at=NOW() WHERE menu_code='G4' AND CAST(menu_name AS BINARY)=CAST('Genesis 经济' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='创世节点销售与收益', updated_at=NOW() WHERE menu_code='G4' AND CAST(menu_name_zh AS BINARY)=CAST('Genesis 经济' AS BINARY);
UPDATE nx_admin_menu SET menu_name='锁定计划', updated_at=NOW() WHERE menu_code='G7' AND CAST(menu_name AS BINARY)=CAST('复投激励' AS BINARY);
UPDATE nx_admin_menu SET menu_name_zh='锁定计划', updated_at=NOW() WHERE menu_code='G7' AND CAST(menu_name_zh AS BINARY)=CAST('复投激励' AS BINARY);

UPDATE nx_admin_permission SET permission_name='F2 团队奖励设置 - 读', updated_at=NOW() WHERE permission_code='network_f2_read' AND CAST(permission_name AS BINARY)=CAST('F2 网络版税费率 - 读' AS BINARY);
UPDATE nx_admin_permission SET permission_name='F2 团队奖励设置 - 常规写', updated_at=NOW() WHERE permission_code='network_f2_write' AND CAST(permission_name AS BINARY)=CAST('F2 网络版税费率 - 常规写' AS BINARY);
UPDATE nx_admin_permission SET permission_name='L1-L7 团队购买奖励比例放宽', updated_at=NOW() WHERE permission_code='network_f2_royalty_rate' AND CAST(permission_name AS BINARY)=CAST('L1-L7 版税费率放宽' AS BINARY);
UPDATE nx_admin_permission SET permission_name='F3 团队平衡奖励 - 读', updated_at=NOW() WHERE permission_code='network_f3_read' AND CAST(permission_name AS BINARY)=CAST('F3 双轨结算引擎 - 读' AS BINARY);
UPDATE nx_admin_permission SET permission_name='F3 团队平衡奖励 - 常规写', updated_at=NOW() WHERE permission_code='network_f3_write' AND CAST(permission_name AS BINARY)=CAST('F3 双轨结算引擎 - 常规写' AS BINARY);
UPDATE nx_admin_permission SET permission_name='暂停团队平衡奖励发放', updated_at=NOW() WHERE permission_code='network_f3_engine_pause' AND CAST(permission_name AS BINARY)=CAST('暂停双轨结算引擎' AS BINARY);
UPDATE nx_admin_permission SET permission_name='F5 奖励记录核对 - 读', updated_at=NOW() WHERE permission_code='network_f5_read' AND CAST(permission_name AS BINARY)=CAST('F5 佣金事件审计 - 读' AS BINARY);
UPDATE nx_admin_permission SET permission_name='F5 奖励记录核对 - 常规写', updated_at=NOW() WHERE permission_code='network_f5_write' AND CAST(permission_name AS BINARY)=CAST('F5 佣金事件审计 - 常规写' AS BINARY);
UPDATE nx_admin_permission SET permission_name='奖励冻结与解冻', updated_at=NOW() WHERE permission_code='network_f5_commission_freeze' AND CAST(permission_name AS BINARY)=CAST('佣金冻结与解冻' AS BINARY);
UPDATE nx_admin_permission SET permission_name='暂停用户奖励', updated_at=NOW() WHERE permission_code='network_f5_commission_suspend' AND CAST(permission_name AS BINARY)=CAST('暂停用户佣金' AS BINARY);
UPDATE nx_admin_permission SET permission_name='奖励提前解锁与发放', updated_at=NOW() WHERE permission_code='network_f5_commission_release' AND CAST(permission_name AS BINARY)=CAST('佣金提前解锁与支付' AS BINARY);
UPDATE nx_admin_permission SET permission_name='奖励撤销', updated_at=NOW() WHERE permission_code='network_f5_commission_reverse' AND CAST(permission_name AS BINARY)=CAST('佣金冲正' AS BINARY);
UPDATE nx_admin_permission SET permission_name='奖励补发', updated_at=NOW() WHERE permission_code='network_f5_commission_reissue' AND CAST(permission_name AS BINARY)=CAST('佣金补发' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G1 调参考年奖励率(调高放大流出·过B1)', updated_at=NOW() WHERE permission_code='finprod_g1_apy_write' AND CAST(permission_name AS BINARY)=CAST('G1 调 APY(升息放大流出·过B1)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G1 调提前解锁扣款(降低扣款放大流出)', updated_at=NOW() WHERE permission_code='finprod_g1_penalty_write' AND CAST(permission_name AS BINARY)=CAST('G1 调提前赎回罚款(降罚放大流出)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G4 创世节点销售与收益页面读', updated_at=NOW() WHERE permission_code='finprod_g4_read' AND CAST(permission_name AS BINARY)=CAST('G4 Genesis 经济页面读' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G4 每日收益比例(调高放大收益负债·过B1)', updated_at=NOW() WHERE permission_code='finprod_g4_dividend_rate_write' AND CAST(permission_name AS BINARY)=CAST('G4 每日分红率(升率放大分红负债·过B1)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G7 锁定计划页面读', updated_at=NOW() WHERE permission_code='finprod_g7_read' AND CAST(permission_name AS BINARY)=CAST('G7 复投激励页面读' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G7 锁定计划常规写(抽奖券/提前解锁扣款/preset)', updated_at=NOW() WHERE permission_code='finprod_g7_write' AND CAST(permission_name AS BINARY)=CAST('G7 复投常规写(抽奖券/早赎罚/preset)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G7 锁定计划参考年奖励率(调高放大流出·过B1)', updated_at=NOW() WHERE permission_code='finprod_g7_apy_write' AND CAST(permission_name AS BINARY)=CAST('G7 复投年化APY(升息放大流出·过B1)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G7 好友升级奖励倍率(调高放大奖励负债·过B1)', updated_at=NOW() WHERE permission_code='finprod_g7_nurture_write' AND CAST(permission_name AS BINARY)=CAST('G7 培育奖倍率(升倍率放大奖励负债·过B1)' AS BINARY);

UPDATE nx_admin_permission SET permission_name='G4 转让手续费(改费率影响转让市场流转)', updated_at=NOW() WHERE permission_code='finprod_g4_royalty_write' AND CAST(permission_name AS BINARY)=CAST('G4 二级版税(改版税影响二级流转)' AS BINARY);
UPDATE nx_admin_permission SET permission_name='G4 认购与转让市场熔断/恢复(止血·同步J1)', updated_at=NOW() WHERE permission_code='finprod_g4_market_toggle' AND CAST(permission_name AS BINARY)=CAST('G4 一二级市场熔断/恢复(止血·同步J1)' AS BINARY);

COMMIT;
