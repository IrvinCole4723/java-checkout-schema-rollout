import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class CheckoutService {
    record CheckoutRequest(String orderId, String customerId, long amountMinor, String destinationCountry) {}
    record OrderUpdate(String orderId, String schema, String checkout, String fulfillment, String receipt, String customerUpdate) {}

    interface ReleaseLedger {
        void save(String key, String json, String idempotencyKey) throws IOException, InterruptedException;
        boolean toggle() throws IOException, InterruptedException;
    }

    private final ReleaseLedger ledger;
    private final Clock clock;
    private final AtomicBoolean schemaV2 = new AtomicBoolean(false);

    CheckoutService(ReleaseLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    OrderUpdate checkout(CheckoutRequest request) throws IOException, InterruptedException {
        validate(request);
        String schema = schemaV2.get() ? "checkout-v2" : "checkout-v1";
        OrderUpdate update = new OrderUpdate(request.orderId(), schema, "authorized", "queued", "issued", "published");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("order_id", request.orderId());
        receipt.put("customer_id", request.customerId());
        receipt.put("amount_minor", request.amountMinor());
        receipt.put("destination_country", request.destinationCountry());
        receipt.put("schema", schema);
        receipt.put("recorded_at", Instant.now(clock).toString());
        ledger.save("receipts/" + request.orderId() + ".json", Json.stringify(receipt), "receipt-" + request.orderId());
        return update;
    }

    boolean toggleRelease() throws IOException, InterruptedException {
        String before = schemaV2.get() ? "checkout-v2" : "checkout-v1";
        String snapshot = Json.object("schema", before, "captured_at", Instant.now(clock).toString());
        ledger.save("snapshots/before-toggle.json", snapshot, "snapshot-before-toggle");
        boolean enabled = ledger.toggle();
        schemaV2.set(enabled);
        return enabled;
    }

    private static void validate(CheckoutRequest request) {
        if (request.orderId() == null || request.orderId().isBlank()) throw new IllegalArgumentException("orderId is required");
        if (request.customerId() == null || request.customerId().isBlank()) throw new IllegalArgumentException("customerId is required");
        if (request.amountMinor() <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        if (request.destinationCountry() == null || request.destinationCountry().length() != 2) throw new IllegalArgumentException("destinationCountry must be ISO-2");
    }
}
