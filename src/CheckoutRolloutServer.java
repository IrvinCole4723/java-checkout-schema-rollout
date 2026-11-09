import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;

public final class CheckoutRolloutServer {
    public static void main(String[] args) throws Exception {
        ServiceConfig config = ServiceConfig.fromEnvironment();
        InfraiClient gateway = new InfraiClient(config);
        gateway.createReleaseBucket();
        CheckoutService.ReleaseLedger ledger = new CheckoutService.ReleaseLedger() {
            public void save(String key, String json, String idempotencyKey) throws java.io.IOException, InterruptedException {
                gateway.putJson(key, json, idempotencyKey);
            }
            public boolean toggle() throws java.io.IOException, InterruptedException { return gateway.toggleCheckoutSchema(); }
        };
        CheckoutService service = new CheckoutService(ledger, Clock.systemUTC());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        CheckoutController controller = new CheckoutController(service);
        server.createContext("/checkout", controller);
        server.createContext("/release/toggle", controller);
        server.start();
        System.out.println("checkout rollout service listening on http://localhost:" + config.port());
    }
}
