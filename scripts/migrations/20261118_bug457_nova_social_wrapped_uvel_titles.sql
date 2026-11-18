-- Retire only the canonical titles on the two published v1 templates.
-- Preserve operator-edited translations, bodies, and channel configuration.
UPDATE nx_nova_template
   SET title_zh = IF(BINARY title_zh IN ('Nexion 真实动态', 'NexGrid 真实动态'),
                     'UVEL 真实动态', title_zh),
       title_en = IF(BINARY title_en IN ('Verified Nexion activity', 'Verified NexGrid activity'),
                     'Verified UVEL activity', title_en),
       title_vi = IF(BINARY title_vi IN ('Hoạt động thực trên Nexion', 'Hoạt động thực trên NexGrid'),
                     'Hoạt động thực trên UVEL', title_vi),
       updated_at = NOW()
 WHERE channel_key = 'social' AND version = 'v1' AND status = 'PUBLISHED' AND is_deleted = 0
   AND (BINARY title_zh IN ('Nexion 真实动态', 'NexGrid 真实动态')
     OR BINARY title_en IN ('Verified Nexion activity', 'Verified NexGrid activity')
     OR BINARY title_vi IN ('Hoạt động thực trên Nexion', 'Hoạt động thực trên NexGrid'));

UPDATE nx_nova_template
   SET title_zh = IF(BINARY title_zh IN ('你的 Nexion 回顾', '你的 NexGrid 回顾'),
                     '你的 UVEL 回顾', title_zh),
       title_en = IF(BINARY title_en IN ('Your Nexion recap', 'Your NexGrid recap'),
                     'Your UVEL recap', title_en),
       title_vi = IF(BINARY title_vi IN ('Nhìn lại hành trình Nexion', 'Nhìn lại hành trình NexGrid'),
                     'Nhìn lại hành trình UVEL', title_vi),
       updated_at = NOW()
 WHERE channel_key = 'wrapped' AND version = 'v1' AND status = 'PUBLISHED' AND is_deleted = 0
   AND (BINARY title_zh IN ('你的 Nexion 回顾', '你的 NexGrid 回顾')
     OR BINARY title_en IN ('Your Nexion recap', 'Your NexGrid recap')
     OR BINARY title_vi IN ('Nhìn lại hành trình Nexion', 'Nhìn lại hành trình NexGrid'));
