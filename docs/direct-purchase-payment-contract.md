# Direct purchase payment contract (17/18)

The existing purchase endpoints remain WALLET commands protected by the payment PIN. External purchases use POST /api/app/purchase-payment-quotes, POST /api/app/purchase-payment-intents and authenticated owner GET/lookup. Methods are HDPAY_BANK, CREGIS_USDT_BSC and CREGIS_USDT_ERC20; kinds are ORDER_PAY, TRIAL_CONVERT, TRADE_IN, CAPACITY_REPLACE and CAPACITY_KEEP. ORDER_PAY includes ordinary, promotion, quantity and bundle pending orders.

The server binds owner, kind, original payload, method/network, business pricing, fee version and expiry. An intent permanently binds the original command key and quote. Unknown HDPay creation can only query that intent's original merchantOrderId. Zero rounded external principal is rejected with ZERO_PAYMENT_REQUIRES_WALLET.

An explicit HDPAY payment time must not precede the original intent's created_at; retain that original paidAt and use PAYMENT_BEFORE_ALLOCATION review when it does, blocking both automatic delivery and explicit revalidation delivery.

External subtotal and each payable fee are Decimal with two digits, HALF_UP. The original business amount is preserved as businessAmountUsdt (six digits), the two digit collection principal is subtotalUsdt, and roundingAdjustmentUsdt records subtotal minus business amount. The receipt retains original amountUsdt and separately returns paymentSubtotalUsdt. Channel fees apply once to discounted subtotal; BSC has a separate networkConfirmFeeUsdt, HDPay has none. Supplier actual fees are recorded independently when known. HDPay freezes the current authoritative VND exchange rate once and collects integer dong. USD/USDT 1:1 is not VND conversion authority.

Stock, device slots and quota reservations follow the underlying order and settlement rules. Actual delivery joins the existing purchase transaction and rechecks ownership, applicable source version and eligibility, and product state and stock. Settlement checks and consumes the F4b monthly quota for all five kinds. It applies the E1 lifetime gate to replacement purchases and ordinary order lines whose inventory and lifetime quota still need to be consumed. Trial conversion uses F4b only. The verified payment scope skips PIN checks and wallet principal debits/ledgers, while preserving legitimate independent trial rewards. Durable intent receipt, order link, provider payment record, inventory/devices, events and required delivery audit commit together. A failed delivery rolls back and enters D1 review. Trial accrual pricing freezes its business quote instant; elapsed time alone cannot alter a quoted payment.

BSC purchase creation never calls address/create. It proves a currently unassigned pool address belongs to the configured project and has zero provider/chain balances, binds a fresh canonical allocation block/hash, then atomically changes purpose to PURCHASE_DIRECT and state to DIRECT_PURCHASE with the new intent. The address remains permanently dedicated and is never allocated to a wallet or recycled. An unavailable pool fails before creating an intent. The existing replenishment worker remains responsible for provider address creation and unknown allocation recovery.

Signed callbacks, complete provider history reconciliation and periodic direct-address scans resolve direct ownership before wallet topup handling. Only exact provider and confirmed BSC Transfer proofs authorize fulfilment. Old transfers at/before allocation, short/over/late payments and additional arrivals remain finance exceptions; there is no automatic wallet credit or refund. Already delivered receipts survive later additional arrivals/reorg exceptions. Positive canonical-chain reorg evidence closes the existing Cregis risk gate and marks review; RPC unavailability alone does not establish a reorg.

D1 policy and record APIs use existing finance permissions, CAS, reason and durable idempotency. Manual revalidation uses gate -> intent lock order and one command transaction for CAS, observation, delivery, audit and command result. An audit failure rolls the entire manual command back. Cregis assignment/credit switches and risk gate apply to direct purchases. Policy output publishes authoritative bounds. Cregis minimum remains under its existing A2 maker/checker publication; this policy API cannot change it.

Wallet recharge principal requests must be positive integers and meet their authoritative channel minimum. HDPay reads its minimum from the D1 payment policy, without a hardcoded minimum. New Cregis discoveries preserve their existing policy snapshot and hold noninteger/below-min arrivals for exception handling. Historical snapshots are not rewritten. Dedicated direct arrivals remain decimal and cannot use the recharge claim/refund disposition path.
Legacy wallet request DTOs explicitly reject any supplied paymentMethod other than WALLET at HTTP binding, even when a valid PIN is supplied; omission retains WALLET behavior. External payloads therefore cannot silently execute a wallet purchase.
External channel fees are fail-closed until each channel is explicitly configured. The policy publishes configured:false with null fees for an unknown channel. Partial policy PATCH accepts one or two complete channels, only marking those submitted entries configured; explicit zero is a valid approved free policy. New quotes/intent creation reject missing fee configuration, while already initiated intents preserve their snapshots.

## Replacement source check (B57-BE-02)

For `TRADE_IN` and `CAPACITY_REPLACE`, `POST /api/app/purchase-payment-quotes` and `POST /api/app/purchase-payment-intents` check the old device with the same read-only runtime guard used at submission. A rejection creates no quote or payment intent for that request, allocates no collection address and creates no provider payment order. If intent creation is rejected, its original quote remains under the existing validity rules.

| HTTP status | Response `message` | Reason |
| --- | --- | --- |
| 409 | `CAPACITY_REPLACEMENT_SOURCE_CHANGED` | For `CAPACITY_REPLACE`, the requested `payload.sourceDeviceId` must match the current replacement candidate. Both endpoints verify the match before checking the device runtime. |
| 409 | `SIMULATED_DEVICE_BUSY` | The simulated device runtime has not drained. |
| 409 | `COMPUTE_V2_DEVICE_BUSY` | The Compute V2 device runtime has not drained. |
| 409 | `COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED` | An earlier execution request is unresolved. |
| 409 | `COMPUTE_V2_OWNER_CHANGED` | The runtime ownership record no longer matches the source device and owner. |
| 503 | `COMPUTE_V2_RUNTIME_RECOVERING` | The Compute V2 runtime is temporarily unavailable. Retry the request. |

Quote requests check payment channel readiness before checking the device. Intent creation rechecks the device for a new intent. Replaying a successful creation with its original key and quote returns the existing intent. A device can become busy after an intent is created, in which case confirmed payment still enters `MANUAL_REVIEW` if delivery fails the replacement guard.

## Purchase quota checks (BUG-549 phase 1)

For bank and USDT payments, both quote creation and new intent creation read the F4b tier, buyer requirements and active usage for the current business month. The requested quantity must fit the remaining monthly quota. A quota rejection creates no quote, intent, collection address assignment or provider payment order for that request. A rejected intent request leaves its original quote subject to the existing validity rules.

For the same owner and purchase kind, an in-progress intent takes precedence over channel readiness, address selection, pricing and eligibility checks. It returns HTTP 409 `PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED`. A delivery receipt establishes a completed purchase even if later financial observations move the intent to `MANUAL_REVIEW`. `ORDER_PAY` matches by order number. `TRADE_IN` and `CAPACITY_REPLACE` match by source device and canonical target product, and `CAPACITY_KEEP` matches by canonical target product. A target ID and a product number resolve to the same identity when they name the same product. Trial conversion matches by the trial claim, so refreshed amounts and quote versions still refer to the same business. Intent creation first handles original-key replay and quote reuse, checks for an in-progress intent before candidate address selection, and repeats those checks in the creation transaction.

`ORDER_PAY` uses the stored product and quantity for a single order and the stored product and quantity for each bundle line. Quantities for the same F4b tier are summed. E1 evaluates the current SKU gate with the buyer facts used at settlement, then compares each eligible line's quantity against the remaining lifetime quota. It skips legacy orders with `inventory_reserved` true or null and lines already marked `lifetime_quota_reserved`. The three replacement kinds check one unit of the canonical target from their existing server quote and retain the quote's E1 evaluation. Trial conversion checks the current conversion switch and the claim's actual conversion deadline. It checks one unit of the product resolved from the claim and requires the requested product to match.

| HTTP status | Response `message` | F4b reason |
| --- | --- | --- |
| 409 | `ORDER_MONTHLY_QUOTA_PAUSED` | The tier is paused. |
| 409 | `ORDER_MONTHLY_QUOTA_REQUIREMENTS_NOT_MET` | The buyer does not meet the tier's requirements. |
| 409 | `ORDER_MONTHLY_QUOTA_EXHAUSTED` | The remaining quota is below the requested quantity. |
| 503 | `ORDER_MONTHLY_QUOTA_UNAVAILABLE` | Tier configuration or quota facts are unavailable. Retry the request. |

| Kind | POST `/api/app/purchase-payment-quotes` | POST `/api/app/purchase-payment-intents` (new intent) |
| --- | --- | --- |
| `ORDER_PAY` | In-progress intent, F4b and order E1 | In-progress intent, F4b and order E1 |
| `TRADE_IN` | In-progress intent, F4b and replacement E1 | In-progress intent, F4b and replacement E1 |
| `CAPACITY_REPLACE` | In-progress intent, F4b and replacement E1 | In-progress intent, F4b and replacement E1 |
| `CAPACITY_KEEP` | In-progress intent, F4b and replacement E1 | In-progress intent, F4b and replacement E1 |
| `TRIAL_CONVERT` | In-progress intent, F4b and trial conversion checks | In-progress intent, F4b and trial conversion checks |

| Check | HTTP status | Response `message` | Reason |
| --- | --- | --- | --- |
| In-progress intent | 409 | `PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED` | An intent is already in progress for the same business. |
| Order E1 | 409 | `PURCHASE_GATE_NOT_MET` | The buyer does not meet the current E1 purchase conditions. |
| Order E1 | 409 | `PURCHASE_GATE_SOLD_OUT` | The E1 lifetime counter has reached the cap. |
| Order E1 | 409 | `PURCHASE_GATE_INVALID` | The product's E1 gate configuration cannot be evaluated. |
| Order E1 | 503 | `PURCHASE_GATE_FACTS_UNAVAILABLE` | The buyer facts needed by the E1 gate are temporarily unavailable. Retry the request. |

Before payment the order checks return these exact E1 reasons. Settlement of a paid order (wallet `/pay` or delivery of a confirmed external payment) still reports any E1 gate refusal as 409 `PURCHASE_GATE_BLOCKED`.

| Order E1 | 409 | `PURCHASE_GATE_SOLD_OUT` | The gate allowed the purchase, but the line quantity exceeds the remaining quota or the quota generation is invalid. |
| Replacement E1 | 403 | `PURCHASE_GATE_SOLD_OUT` | The target's lifetime quota is exhausted. |
| Replacement E1 | 403 | `PURCHASE_GATE_NOT_MET` | The buyer does not meet the target's requirements. |
| Replacement E1 | 403 | `PURCHASE_GATE_INVALID` | The target's gate configuration is invalid. |
| Replacement E1 | 403 | `PURCHASE_GATE_FACTS_UNAVAILABLE` | Buyer facts are unavailable. |
| Replacement E1 | 403 | `PURCHASE_GATE_FACTS_INVALID` | Buyer facts are invalid. |
| Trial conversion checks | 409 | `TRIAL_KILL_SWITCH_DISABLED` | Trial conversion is disabled. |
| Trial conversion checks | 409 | `TRIAL_NOT_CONVERTIBLE` | The claim is inactive or its conversion window has closed. |
| Trial conversion checks | 422 | `TRIAL_PRODUCT_REQUIRED` | The requested trial conversion product is missing or malformed. |
| Trial conversion checks | 409 | `TRIAL_PRODUCT_NOT_ELIGIBLE` | The requested product does not match the claim's conversion product, or the claim has no valid configured product. |

Quota remains available to other purchasers while an intent awaits payment. A quota failure during confirmed payment delivery can still send the intent to `MANUAL_REVIEW`.

## 2026-11-01 deadline and result contract

ORDER_PAY expires at its original order creation + 30 minutes, capped by an earlier promotion pay_by. Other kinds start with a deadline of 30 minutes from the immutable business quote instant. For TRIAL_CONVERT, the quote's payment deadline is capped at the claim's current conversion cutoff. Intent creation uses the earlier of the quoted payment deadline and the claim's current cutoff. Quotes remain valid for at most 15 minutes and expire by their payment deadline. A new intent's expiresAt is its payment deadline. Responses also return confirmationGraceEndsAt and paymentAllowed. After the effective deadline, user responses suppress paymentUrl/address; the permanent supplier/address association remains available for reconciliation. A submitted hosted page or blockchain address cannot be revoked locally.

Confirmation grace is a reconciliation window. It ends at the stored intent deadline + 3 minutes for BEP20, + 5 minutes for ERC20 and bank. It only accepts proof of payment strictly before the effective payment deadline, observed strictly before grace ends. Canonical chain block timestamps bind the same block hash as the verified Transfer. Provider timestamps must come from a documented supplier field, never the current query time. If bank success is first observed before the deadline, that observation proves an upper bound on payment time; it does not fabricate supplier paid_at. Success first observed after cutoff without payment-time evidence enters PAYMENT_TIME_UNVERIFIED; proof of late payment or observation at/after grace enters PAYMENT_LATE_REVIEW. HDPAY expirationTime, when verified and present, caps the payment deadline; grace remains anchored to the stored intent deadline.

The generic order expiry scheduler excludes every order with a direct-payment history, including failed retryable attempts, and rechecks that ownership under the shared user mutex. These orders are ended only by the fixed direct-payment deadline/grace observer; legacy generic TTL changes cannot shorten or extend their window. At grace end the direct observer queries the original identity first, then commits intent EXPIRED/PAYMENT_TIMEOUT, order expiry with PAYMENT_TIMEOUT and existing inventory/quota/voucher/promotion release together. An unresolved query is durably recorded as reconciliationStatus=QUERY_UNKNOWN. EXPIRED intents remain in scheduled original-identity observation, without a replacement collection or automatic refund. Each run reserves 50 observations for active commerce attempts (including EXPIRED failures while their canonical order remains PENDING) and a separate 10 for historical/review/delivered attempts; even failed queries advance their historical rotation position. Historical volume cannot consume the active batch. A query outage does not extend the payment window: after that original query attempt, timeout still closes the order; any subsequent arrival is reviewed. No new unpaid order increments inventory on release. A failed old attempt cannot cancel an active successor.

HDPAY 5 produces EXPIRED/PAYMENT_FAILED and permits a fresh quote/new command key before cutoff; the original key remains bound to the original intent. HDPAY 4 produces MANUAL_REVIEW/PAYMENT_REFUNDED_REVIEW, including a refund discovered after delivery. Neither refund detection nor late-arrival review creates wallet money or automatically reverses delivered devices. Finance handles these exceptions.

Durable result facts commit with DELIVERED, PAYMENT_TIMEOUT or MANUAL_REVIEW. The existing business notification projector creates one message and native push identity per result. Order timeout deduplicates across failed/retried payment identities; other results deduplicate per intent. Projection and provider retries never re-run financial settlement. Order notification routes use /pages/store/order-detail?id= and native resolve rechecks the authenticated recipient owns the order.

Apply scripts/migrations/20261101_purchase_payment_deadlines.sql through the normal startup manifest before deploying this service. It only appends evidence/result schema and aligns all unresolved ORDER_PAY deadlines on still-pending orders, including failed attempts; it does not enable channels, set fees, allocate external accounts or rewrite historical delivered receipts. Actual hosted-page integration and merchant timestamp semantics still require TEST verification; no synthetic fixture is a real merchant acceptance.

The [supplied HDPAY interface documentation](https://www.eolink.com/share/inside/7e6a0a7950f9c43ceee62f6085d8400a/doc/630897/detail/769055), reviewed on 2026-11-01, names pay-in query fields expirationTime and payTime (successful payment time). Creation returns only a hosted URL and documents no requested lifetime. The pay-in examples use local date strings without an offset and specify no timezone; a separate payout example cannot establish the pay-in timezone or the merchant's actual lifetime. The HTTP adapter maps only these two named fields when their own ISO date strings carry Z or an explicit numeric offset. Missing, null, malformed, nontext and offset-free dates remain unknown evidence. It never substitutes createTime, an undocumented paidAt alias, country or application timezone. The documented offset-free examples therefore still cannot authorize post-deadline automatic delivery or establish an earlier absolute provider expiry; existing manual review remains in force. Explicit-offset parsing does not establish that the merchant actually emits that format.
