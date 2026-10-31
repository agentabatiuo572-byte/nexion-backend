-- KYC is retired; retain its existing historical catalog metadata and facts.
UPDATE nx_behavior_page_catalog
SET tracked=0,updated_at=updated_at
WHERE BINARY route=BINARY '/pages/me/kyc' AND tracked=1;
