import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class CheckoutController implements HttpHandler {
    private final CheckoutService service;

    CheckoutController(CheckoutService service) { this.service = service; }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Json.object("error", "POST required")); return; }
            if ("/release/toggle".equals(exchange.getRequestURI().getPath())) {
                boolean enabled = service.toggleRelease();
                respond(exchange, 200, Json.object("checkoutSchemaV2", enabled, "rollback", "POST /release/toggle"));
                return;
            }
            if (!"/checkout".equals(exchange.getRequestURI().getPath())) { respond(exchange, 404, Json.object("error", "route not found")); return; }
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            CheckoutService.CheckoutRequest request = new CheckoutService.CheckoutRequest(
                    (String) body.get("orderId"), (String) body.get("customerId"), ((Number) body.getOrDefault("amountMinor", 0)).longValue(), (String) body.get("destinationCountry"));
            CheckoutService.OrderUpdate result = service.checkout(request);
            respond(exchange, 200, Json.object("orderId", result.orderId(), "schema", result.schema(), "checkout", result.checkout(),
                    "fulfillment", result.fulfillment(), "receipt", result.receipt(), "customerUpdate", result.customerUpdate()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Json.object("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); respond(exchange, 503, Json.object("error", "request interrupted"));
        } catch (IOException e) {
            respond(exchange, 502, Json.object("error", "release dependency rejected the request"));
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
