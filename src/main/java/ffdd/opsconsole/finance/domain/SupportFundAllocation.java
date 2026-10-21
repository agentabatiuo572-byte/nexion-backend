package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replays proven fund operations; it neither authorizes wallet writes nor proves their provenance. */
public final class SupportFundAllocation {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    public enum Action { DEPOSIT, EARNINGS, PROTECTED_EARNINGS, CONSUME, FEE, REFUND, RESERVE, CONFIRM, RETURN, TRADE, MOVE, EXCHANGE, RELEASE, RECOVER, CLAWBACK,
        REORG_DEBIT, REORG_REFUND, REVOKE_DEPOSIT, RESTORE_DEPOSIT }
    public record Account(long customerId, String currency, String slot) {
        public Account {
            require(customerId > 0 && ("USDT".equals(currency) || "NEX".equals(currency)), "Invalid account");
            require(slot != null && (slot.equals("available") || slot.matches("(?:held|risk):[^\\s:]{1,128}")), "Invalid fund slot");
        }
    }
    public record Event(String id, Action action, Account from, Account to, String reference, BigDecimal amount, BigDecimal targetAmount) {
        public Event {
            require(id != null && !id.isBlank() && id.equals(id.trim()) && id.length() <= 192 && action != null, "Invalid operation");
            amount = amount == null ? null : money(amount);
            targetAmount = targetAmount == null ? null : money(targetAmount);
        }
    }
    public record Change(String contributionId, String currency, BigDecimal amount) {}
    private record Lot(String originId, long originOrder, long contributionOrder, boolean withdrawable, String contributionId, String basisCurrency, BigDecimal quantity, BigDecimal basis) {
        Lot part(BigDecimal quantity, BigDecimal basis) { return new Lot(originId, originOrder, contributionOrder, withdrawable, contributionId, basisCurrency, quantity, basis); }
    }
    private record Taken(List<Lot> picked, List<Lot> remaining) {}
    private record Consumption(Account account, List<Lot> remaining, boolean contributes) {}
    private record Reservation(Account account, List<Lot> lots, boolean terminal) {}
    private Map<Account, List<Lot>> balances = new HashMap<>();
    private Map<String, Event> receipts = new HashMap<>();
    private Map<String, Consumption> consumed = new HashMap<>();
    private Map<String, Reservation> reserved = new HashMap<>();
    private Map<String, BigDecimal> totals = new HashMap<>();
    private Set<String> released = new HashSet<>();
    private Map<String,BigDecimal> contributionBalances = new HashMap<>();
    private Set<String> revokedDeposits = new HashSet<>();

    public BigDecimal balance(Account account) { return sum(balances.getOrDefault(account, List.of())); }
    /** Exact non-cash holdings; the reader must independently reconcile every returned slot. */
    public Map<Account,BigDecimal> heldBalances() {
        Map<Account,BigDecimal> result=new HashMap<>();
        balances.forEach((account,lots)->{BigDecimal amount=sum(lots);
            if(!available(account) && amount.signum()>0)result.put(account,amount);});
        return Map.copyOf(result);
    }
    public BigDecimal pendingWithdrawal(Account account) {
        require(available(account), "Invalid withdrawal account");
        return reserved.values().stream().filter(r -> !r.terminal() && r.account().equals(account))
                .flatMap(r -> r.lots().stream()).map(Lot::quantity).reduce(ZERO, BigDecimal::add);
    }
    public BigDecimal total(String currency) {
        require("USDT".equals(currency) || "NEX".equals(currency), "Invalid currency");
        return totals.getOrDefault(currency, ZERO);
    }

    public List<Change> apply(Event event) {
        require(event != null, "Missing operation");
        return applyBatch(List.of(event));
    }

    public List<Change> applyBatch(List<Event> events) {
        require(events != null && !events.isEmpty(), "Missing operation events");
        // Work on immutable lots in copied maps so a rejected operation cannot leave a partial allocation.
        SupportFundAllocation next = new SupportFundAllocation();
        next.balances = new HashMap<>(balances);
        next.receipts = new HashMap<>(receipts);
        next.consumed = new HashMap<>(consumed);
        next.reserved = new HashMap<>(reserved);
        next.totals = new HashMap<>(totals);
        next.released = new HashSet<>(released);
        next.contributionBalances = new HashMap<>(contributionBalances);
        next.revokedDeposits = new HashSet<>(revokedDeposits);
        List<Change> changes = new ArrayList<>();
        for (Event event : events) {
            require(event != null, "Missing operation event");
            Event previous = next.receipts.get(event.id());
            if (previous != null) {
                require(previous.equals(event), "Conflicting operation receipt");
                continue;
            }
            validate(event);
            next.execute(event, changes);
            next.receipts.put(event.id(), event);
        }
        balances = next.balances;
        receipts = next.receipts;
        consumed = next.consumed;
        reserved = next.reserved;
        totals = next.totals;
        released = next.released;
        contributionBalances = next.contributionBalances;
        revokedDeposits = next.revokedDeposits;
        return List.copyOf(changes);
    }

    private void execute(Event e, List<Change> changes) {
        switch (e.action()) {
            case DEPOSIT, EARNINGS, PROTECTED_EARNINGS -> {
                boolean credited = e.action() == Action.DEPOSIT;
                add(e.to(), List.of(new Lot(e.id(), receipts.size(), receipts.size(), e.action() != Action.PROTECTED_EARNINGS,
                        credited ? e.id() : null, e.to().currency(), e.amount(), credited ? e.amount() : ZERO)));
                if (credited) change(changes, e.id(), e.to().currency(), e.amount());
            }
            case CONSUME, FEE, TRADE -> {
                Taken take = take(e.from(), e.amount(), false);
                List<Lot> spent = new ArrayList<>();
                for (Lot lot : take.picked()) {
                    if (lot.contributionId() == null && e.action() != Action.FEE) {
                        change(changes, e.id(), e.from().currency(), lot.quantity());
                        spent.add(new Lot(lot.originId(), lot.originOrder(), receipts.size(), true, e.id(), e.from().currency(), lot.quantity(), lot.quantity()));
                    } else spent.add(lot);
                }
                balances.put(e.from(), take.remaining());
                if (e.action() != Action.TRADE) consumed.put(e.id(), new Consumption(e.from(), take.picked(), e.action() == Action.CONSUME));
                else add(e.to(), split(spent, e.targetAmount()).picked());
            }
            case REFUND -> {
                Consumption purchase = consumed.get(e.reference());
                require(purchase != null, "Refund requires an original consumption");
                Taken take = split(purchase.remaining(), e.amount());
                for (Lot original : take.picked()) {
                    if (purchase.contributes() && original.contributionId() == null) change(changes, e.reference(), purchase.account().currency(), original.quantity().negate());
                }
                add(purchase.account(), take.picked());
                consumed.put(e.reference(), new Consumption(purchase.account(), take.remaining(), purchase.contributes()));
            }
            case RESERVE -> {
                Taken take = take(e.from(), e.amount(), true);
                balances.put(e.from(), take.remaining());
                reserved.put(e.id(), new Reservation(e.from(), take.picked(), false));
            }
            case CONFIRM, RETURN -> {
                Reservation withdrawal = reserved.get(e.reference());
                require(withdrawal != null && !withdrawal.terminal(), "Withdrawal is missing or already terminal");
                if (e.action() == Action.RETURN) add(withdrawal.account(), withdrawal.lots());
                else for (Lot lot : withdrawal.lots()) {
                    if (lot.contributionId() != null) change(changes, lot.contributionId(), lot.basisCurrency(), lot.basis().negate());
                }
                reserved.put(e.reference(), new Reservation(withdrawal.account(), withdrawal.lots(), true));
            }
            case MOVE, EXCHANGE -> {
                Taken take = take(e.from(), e.amount(), false);
                if (e.action() == Action.MOVE) add(e.to(), take.picked());
                else {
                    List<Lot> converted = new ArrayList<>();
                    BigDecimal remaining = e.targetAmount();
                    for (int i = 0; i < take.picked().size(); i++) {
                        Lot lot = take.picked().get(i);
                        BigDecimal quantity = i == take.picked().size() - 1 ? remaining
                                : e.targetAmount().multiply(lot.quantity()).divide(e.amount(), 6, RoundingMode.DOWN);
                        // shortcut: sub-six-decimal source splits require UNKNOWN; upgrade to exact rational allocation if supported FX produces them.
                        require(quantity.signum() > 0, "Exchange cannot preserve every source at asset precision");
                        converted.add(lot.part(quantity, lot.basis()));
                        remaining = remaining.subtract(quantity);
                    }
                    add(e.to(), converted);
                }
                balances.put(e.from(), take.remaining());
            }
            case RELEASE -> {
                Event origin = receipts.get(e.reference());
                require(origin != null && (origin.action() == Action.EARNINGS || origin.action() == Action.PROTECTED_EARNINGS)
                        && origin.to().customerId() == e.from().customerId() && origin.to().currency().equals(e.from().currency()), "Release requires the original earnings");
                released.add(e.reference());
            }
            case RECOVER -> {
                Event origin=receipts.get(e.reference());
                require(origin!=null && (origin.action()==Action.EARNINGS || origin.action()==Action.PROTECTED_EARNINGS)
                    && origin.to().equals(e.from()),"Recovery requires the original earnings account");
                List<Lot> remaining=new ArrayList<>();BigDecimal needed=e.amount();
                for(Lot lot:balances.getOrDefault(e.from(),List.of())) {
                    BigDecimal quantity=lot.originId().equals(e.reference()) && lot.contributionId()==null
                        ? needed.min(lot.quantity()):ZERO;
                    if(quantity.signum()>0) {
                        BigDecimal basis=basisPart(lot,quantity);needed=needed.subtract(quantity);
                        if(quantity.compareTo(lot.quantity())<0)remaining.add(lot.part(lot.quantity().subtract(quantity),lot.basis().subtract(basis)));
                    }else remaining.add(lot);
                }
                require(needed.signum()==0,"Insufficient proven unspent original earnings");
                balances.put(e.from(),List.copyOf(remaining));
            }
            case CLAWBACK -> {
                Event origin=receipts.get(e.reference());
                require(origin!=null && (origin.action()==Action.EARNINGS || origin.action()==Action.PROTECTED_EARNINGS)
                    && origin.to().equals(e.from()),"Clawback requires the original commission account");
                BigDecimal recovered=receipts.values().stream().filter(previous->e.reference().equals(previous.reference())
                    && (previous.action()==Action.RECOVER ||previous.action()==Action.CLAWBACK))
                    .map(Event::amount).reduce(ZERO,BigDecimal::add);
                require(recovered.add(e.amount()).compareTo(origin.amount())<=0,"Clawback exceeds the original commission");
                var remaining=new ArrayList<>(balances.getOrDefault(e.from(),List.of()));
                remaining.sort(Comparator.comparingLong(Lot::originOrder));
                BigDecimal needed=e.amount();
                // Existing commission recovery may legally use later available funds; it is not a new purchase.
                for(int priority=0;priority<2 &&needed.signum()>0;priority++)for(int i=0;i<remaining.size() &&needed.signum()>0;i++) {
                    Lot lot=remaining.get(i);boolean original=lot.originId().equals(e.reference());
                    if((priority==0)!=original ||lot.quantity().signum()==0)continue;
                    BigDecimal quantity=needed.min(lot.quantity()),basis=basisPart(lot,quantity);
                    remaining.set(i,lot.part(lot.quantity().subtract(quantity),lot.basis().subtract(basis)));needed=needed.subtract(quantity);
                }
                require(needed.signum()==0,"Insufficient available funds for commission clawback");
                balances.put(e.from(),remaining.stream().filter(lot->lot.quantity().signum()>0).toList());
            }
            case REORG_DEBIT -> {
                Event origin=receipts.get(e.reference());
                require(origin!=null && origin.action()==Action.DEPOSIT && origin.to().customerId()==e.from().customerId()
                    && origin.to().currency().equals(e.from().currency()),"Reorg debit requires the original deposit");
                Taken take=take(e.from(),e.amount(),false);
                balances.put(e.from(),take.remaining());
                consumed.put(e.id(),new Consumption(e.from(),take.picked(),false));
            }
            case REORG_REFUND -> {
                Event debit=receipts.get(e.reference());Consumption collection=consumed.get(e.reference());
                require(debit!=null && debit.action()==Action.REORG_DEBIT && collection!=null
                    && collection.account().customerId()==e.to().customerId() && collection.account().currency().equals(e.to().currency()),
                    "Reorg return requires the original collected funds");
                Taken take=split(collection.remaining(),e.amount());add(e.to(),take.picked());
                consumed.put(e.reference(),new Consumption(collection.account(),take.remaining(),false));
            }
            case REVOKE_DEPOSIT, RESTORE_DEPOSIT -> {
                Event origin=receipts.get(e.reference());
                require(origin!=null && origin.action()==Action.DEPOSIT && origin.amount().compareTo(e.amount())==0,
                    "Reorg contribution adjustment requires the original deposit");
                BigDecimal current=contributionBalances.getOrDefault(e.reference(),ZERO);
                boolean reverse=e.action()==Action.REVOKE_DEPOSIT;
                require(reverse?revokedDeposits.add(e.reference()):revokedDeposits.remove(e.reference()),"Deposit contribution state conflict");
                BigDecimal delta=reverse?current.negate():current;
                if(delta.signum()!=0){changes.add(new Change(e.reference(),origin.to().currency(),delta));totals.merge(origin.to().currency(),delta,BigDecimal::add);}
            }
        }
    }

    private Taken take(Account account, BigDecimal amount, boolean withdrawal) {
        List<Lot> current = balances.getOrDefault(account, List.of());
        List<Lot> selected = new ArrayList<>();
        List<Lot> remaining = new ArrayList<>(current);
        BigDecimal needed = amount;
        for (int priority = 0; priority < 2 && needed.signum() > 0; priority++) {
            for (int i = 0; i < current.size() && needed.signum() > 0; i++) {
                Lot lot = remaining.get(i);
                boolean credited = lot.contributionId() != null;
                int rank = withdrawal ? (credited ? 1 : 0) : (credited ? 0 : 1);
                if (lot.quantity().signum() == 0 || rank != priority || (withdrawal && !lot.withdrawable() && !released.contains(lot.originId()))) continue;
                BigDecimal quantity = needed.min(lot.quantity());
                BigDecimal basis = basisPart(lot, quantity);
                selected.add(lot.part(quantity, basis));
                remaining.set(i, lot.part(lot.quantity().subtract(quantity), lot.basis().subtract(basis)));
                needed = needed.subtract(quantity);
            }
        }
        require(needed.signum() == 0, "Insufficient proven eligible funds");
        return new Taken(List.copyOf(selected), remaining.stream().filter(l -> l.quantity().signum() > 0).toList());
    }

    private static Taken split(List<Lot> lots, BigDecimal amount) {
        require(amount.compareTo(sum(lots)) <= 0, "Amount exceeds original funds");
        List<Lot> picked = new ArrayList<>();
        List<Lot> remaining = new ArrayList<>();
        BigDecimal needed = amount;
        for (Lot lot : lots) {
            BigDecimal quantity = needed.min(lot.quantity());
            BigDecimal basis = basisPart(lot, quantity);
            if (quantity.signum() > 0) picked.add(lot.part(quantity, basis));
            if (quantity.compareTo(lot.quantity()) < 0) remaining.add(lot.part(lot.quantity().subtract(quantity), lot.basis().subtract(basis)));
            needed = needed.subtract(quantity);
        }
        return new Taken(List.copyOf(picked), List.copyOf(remaining));
    }

    private static BigDecimal basisPart(Lot lot, BigDecimal quantity) {
        return quantity.compareTo(lot.quantity()) == 0 ? lot.basis()
                : lot.basis().multiply(quantity).divide(lot.quantity(), 6, RoundingMode.DOWN);
    }
    private void add(Account account, List<Lot> lots) {
        List<Lot> all = new ArrayList<>(balances.getOrDefault(account, List.of()));
        all.addAll(lots);
        all.sort(java.util.Comparator.comparingLong(Lot::originOrder).thenComparingLong(Lot::contributionOrder));
        balances.put(account, List.copyOf(all));
    }
    private void change(List<Change> changes, String origin, String currency, BigDecimal amount) {
        if (amount.signum() == 0) return;
        contributionBalances.merge(origin,amount,BigDecimal::add);
        if(revokedDeposits.contains(origin))return;
        changes.add(new Change(origin, currency, amount));
        totals.merge(currency, amount, BigDecimal::add);
    }
    private static BigDecimal sum(List<Lot> lots) { return lots.stream().map(Lot::quantity).reduce(ZERO, BigDecimal::add); }
    private static BigDecimal money(BigDecimal value) {
        require(value.signum() > 0 && value.precision() - value.scale() <= 12, "Invalid amount");
        try { return value.setScale(6, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Invalid asset precision", ex); }
    }
    public static void validate(Event e) {
        require(e != null, "Missing operation event");
        switch (e.action()) {
            case DEPOSIT, EARNINGS, PROTECTED_EARNINGS -> require(e.from() == null && e.to() != null && e.reference() == null && e.amount() != null && e.targetAmount() == null, "Invalid credit");
            case CONSUME, RESERVE -> require(available(e.from()) && e.to() == null && e.reference() == null && e.amount() != null && e.targetAmount() == null, "Invalid consumption or withdrawal reservation");
            case FEE -> require(exchangeSlot(e.from()) && e.to()==null &&e.reference()==null &&e.amount()!=null &&e.targetAmount()==null,"Invalid fee source");
            case REFUND -> require(e.from() == null && e.to() == null && reference(e) && e.amount() != null && e.targetAmount() == null, "Invalid refund");
            case CONFIRM, RETURN -> require(e.from() == null && e.to() == null && reference(e) && e.amount() == null && e.targetAmount() == null, "Invalid withdrawal outcome");
            case RELEASE -> require(available(e.from()) && e.to() == null && reference(e) && e.amount() == null && e.targetAmount() == null, "Invalid earnings release");
            case RECOVER -> require(available(e.from()) && e.to()==null && reference(e) && e.amount()!=null && e.targetAmount()==null,"Invalid original earnings recovery");
            case CLAWBACK -> require(available(e.from()) &&e.to()==null &&reference(e) &&e.amount()!=null &&e.targetAmount()==null,"Invalid commission clawback");
            case REORG_DEBIT -> require(e.from()!=null && e.to()==null && reference(e) && e.amount()!=null && e.targetAmount()==null,"Invalid reorg collection");
            case REORG_REFUND -> require(e.from()==null && available(e.to()) && reference(e) && e.amount()!=null && e.targetAmount()==null,"Invalid reorg return");
            case REVOKE_DEPOSIT, RESTORE_DEPOSIT -> require(e.from()==null && e.to()==null && reference(e) && e.amount()!=null && e.targetAmount()==null,"Invalid deposit contribution correction");
            case MOVE, EXCHANGE, TRADE -> {
                require(e.from() != null && e.to() != null && !e.from().equals(e.to()) && e.reference() == null && e.amount() != null, "Invalid transfer");
                if (e.action() == Action.MOVE) require(e.from().customerId() == e.to().customerId() && e.from().currency().equals(e.to().currency()) && e.targetAmount() == null, "Invalid principal move");
                else if (e.action() == Action.EXCHANGE) require(exchangeSlot(e.from()) && exchangeSlot(e.to()) && e.from().customerId() == e.to().customerId() && !e.from().currency().equals(e.to().currency()) && e.targetAmount() != null, "Invalid exchange");
                else require(available(e.from()) && available(e.to()) && e.from().customerId() != e.to().customerId() && e.from().currency().equals(e.to().currency()) && e.targetAmount() != null && e.targetAmount().compareTo(e.amount()) <= 0, "Invalid secondary purchase");
            }
        }
    }
    private static boolean reference(Event e) { return e.reference() != null && !e.reference().isBlank(); }
    private static boolean available(Account account) { return account != null && account.slot().equals("available"); }
    private static boolean exchangeSlot(Account account) {return account!=null &&(available(account) ||account.slot().startsWith("held:"));}
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
