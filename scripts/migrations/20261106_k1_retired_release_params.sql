UPDATE nx_config_item SET status = 0
WHERE status = 1 AND is_deleted = 0
  AND config_key IN ('risk.k1.release.freePhoneSlotsPerCluster',
                     'risk.k1.release.pendingReleaseHours',
                     'risk.k1.release.freeSlotRequiresBinding');
