-- Immediate checkout reductions reuse the promotion reservation/budget authority.
-- No accounts, amounts or permissions are seeded. Each ALTER is safe to re-run.
ALTER TABLE nx_promotion_budget
  DROP CHECK chk_promotion_budget_kind,
  ADD CONSTRAINT chk_promotion_budget_kind CHECK (
    (asset IN ('USDT','NEX','DISCOUNT_USDT') AND product_no='')
    OR (asset='DEVICE' AND product_no<>''));

ALTER TABLE nx_promotion_reservation
  DROP CHECK chk_promotion_reservation_asset,
  DROP CHECK chk_promotion_reservation_state,
  ADD CONSTRAINT chk_promotion_reservation_asset CHECK (
    (asset IN ('USDT','NEX','DISCOUNT_USDT') AND product_no='')
    OR (asset='DEVICE' AND product_no<>'' AND amount=FLOOR(amount))),
  ADD CONSTRAINT chk_promotion_reservation_state CHECK (
    status IN ('RESERVED','COMMITTED','RELEASED')
    OR (asset='DISCOUNT_USDT' AND status='REVERSED'));
