-- 下线的活动：NEX Holders Share、Regional Race、Re-invest Bonus Week（2026-10-29 产品决定删除）。
-- 与后台「删除活动」同一口径：只删从未发布的草稿（status=0），置 is_deleted 墓碑；H4 种子是 INSERT IGNORE，墓碑行不会被重新插回。
-- 已经发布过的不在这里动，由运营在后台结束后再删除。每次启动重跑，幂等。
UPDATE nx_event_quest
   SET is_deleted=1,updated_at=NOW()
 WHERE quest_code IN ('evt-nex-holders-share','evt-regional-pk','evt-reinvest-bonus')
   AND status=0
   AND is_deleted=0;
