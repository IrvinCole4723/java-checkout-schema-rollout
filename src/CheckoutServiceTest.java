import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class CheckoutServiceTest {
    public static void main(String[] args) throws Exception {
        FakeLedger ledger = new FakeLedger();
        CheckoutService service = new CheckoutService(ledger, Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC));
        CheckoutService.CheckoutRequest request = new CheckoutService.CheckoutRequest("ord-2048", "cus-17", 4599, "SG");

        CheckoutService.OrderUpdate before = service.checkout(request);
        check("checkout-v1".equals(before.schema()), "legacy schema must serve checkout before release");

        check(service.toggleRelease(), "release toggle should enable v2");
        CheckoutService.OrderUpdate after = service.checkout(request);
        check("checkout-v2".equals(after.schema()), "new schema must serve checkout after release");
        check("queued".equals(after.fulfillment()) && "issued".equals(after.receipt()) && "published".equals(after.customerUpdate()), "order handoff must be complete");
        check(ledger.keys.get(1).equals("snapshots/before-toggle.json"), "snapshot must precede the flag change");
        System.out.println("PASS: ord-2048 moves from checkout-v1 to checkout-v2 with a pre-toggle snapshot");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class FakeLedger implements CheckoutService.ReleaseLedger {
        final List<String> keys = new ArrayList<>();
        public void save(String key, String json, String idempotencyKey) { keys.add(key); }
        public boolean toggle() { return true; }
    }
}
