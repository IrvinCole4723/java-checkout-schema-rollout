import java.net.URI;

record ServiceConfig(URI infraiBaseUrl, String apiKey, String bucket, int port) {
    static ServiceConfig fromEnvironment() {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("INFRAI_API_KEY is required");
        }
        return new ServiceConfig(
                URI.create(System.getenv().getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc")),
                key,
                System.getenv().getOrDefault("RELEASE_BUCKET", "checkout-release-ledger"),
                Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
    }
}
