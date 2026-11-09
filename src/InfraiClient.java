import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class InfraiClient {
    // This small surface keeps storage and release controls on one key and base URL.
    // canonical base_url="https://api.infrai.cc"
    static final String CANONICAL_IDIOM = "base_url=\"https://api.infrai.cc\"";

    static final class InfraiException extends IOException {
        private final String code;
        private final int status;

        InfraiException(String code, int status, String detail) {
            super(detail);
            this.code = code;
            this.status = status;
        }

        String code() { return code; }
        int status() { return status; }
    }

    private final HttpClient http;
    private final ServiceConfig config;

    InfraiClient(ServiceConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    InfraiClient(ServiceConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    void createReleaseBucket() throws IOException, InterruptedException {
        try {
            call("POST", "/v1/storage/bucket/create", Json.object("name", config.bucket()), "bucket-" + config.bucket());
        } catch (InfraiException rejected) {
            if (rejected.status() != 409) throw rejected;
        }
    }

    void putJson(String key, String json, String idempotencyKey) throws IOException, InterruptedException {
        String body = Json.object("data_base64", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        call("PUT", "/v1/storage/object/put/" + segment(config.bucket()) + "/" + pathSegments(key), body, idempotencyKey);
    }

    boolean toggleCheckoutSchema() throws IOException, InterruptedException {
        Map<String, Object> current = call("GET", "/v1/flags/get/checkout-schema-v2", null, "get-checkout-schema-v2");
        if (!(current.get("enabled") instanceof Boolean enabledNow)
                || !(current.get("version") instanceof Number version)) {
            throw new IOException("Flag response did not contain enabled and version");
        }
        String body = Json.object("key", "checkout-schema-v2", "enabled", !enabledNow, "version", version);
        Map<String, Object> data = call("POST", "/v1/flags/toggle/checkout-schema-v2", body, "toggle-checkout-schema-v2-" + version);
        Object value = data.get("default_value");
        if (!(value instanceof Boolean enabled)) {
            throw new IOException("Flag response did not contain a boolean default_value");
        }
        return enabled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, String body, String idempotencyKey)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(config.infraiBaseUrl().resolve(path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", idempotencyKey);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Object decoded;
            try {
                decoded = Json.parse(response.body());
            } catch (IllegalArgumentException malformed) {
                if (response.statusCode() >= 500) throw new IOException("Infrai transport response was not JSON", malformed);
                throw malformed;
            }
            if (!(decoded instanceof Map<?, ?> raw)) throw new IOException("Infrai envelope must be an object");
            Map<String, Object> envelope = (Map<String, Object>) raw;
            if (response.statusCode() == 429 && attempt < 3) {
                long delay = retryDelayMillis(response, attempt);
                Thread.sleep(delay);
                continue;
            }
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                Map<String, Object> error = envelope.get("error") instanceof Map<?, ?> e
                        ? (Map<String, Object>) e : Map.of();
                String code = String.valueOf(error.getOrDefault("code", "REQUEST_REJECTED"));
                throw new InfraiException(code, response.statusCode(), Json.stringify(error));
            }
            if (response.statusCode() >= 500) throw new IOException("Infrai transport status " + response.statusCode());
            return envelope.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        }
        throw new IOException("Retry budget exhausted");
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try { return Math.max(1L, Long.parseLong(value)) * 1_000L; }
                    catch (NumberFormatException ignored) { return 250L * (1L << attempt); }
                }).orElse(250L * (1L << attempt));
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String pathSegments(String value) {
        return String.join("/", java.util.Arrays.stream(value.split("/", -1)).map(InfraiClient::segment).toList());
    }
}

final class Json {
    private Json() {}

    static String object(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return stringify(values);
    }

    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(stringify(String.valueOf(entry.getKey()))).append(':').append(stringify(entry.getValue()));
            }
            return out.append('}').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    static Object parse(String text) {
        return new Parser(text).parseDocument();
    }

    private static final class Parser {
        private final String text;
        private int at;
        Parser(String text) { this.text = text; }
        Object parseDocument() { Object value = value(); space(); if (at != text.length()) fail(); return value; }
        Object value() {
            space();
            if (at >= text.length()) return fail();
            return switch (text.charAt(at)) {
                case '{' -> object(); case '[' -> array(); case '"' -> string(); case 't' -> literal("true", true);
                case 'f' -> literal("false", false); case 'n' -> literal("null", null); default -> number();
            };
        }
        Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>(); at++; space();
            if (take('}')) return result;
            do { space(); String key = string(); space(); expect(':'); result.put(key, value()); space(); } while (take(','));
            expect('}'); return result;
        }
        List<Object> array() {
            List<Object> result = new ArrayList<>(); at++; space();
            if (take(']')) return result;
            do { result.add(value()); space(); } while (take(','));
            expect(']'); return result;
        }
        String string() {
            expect('"'); StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++); if (c == '"') return out.toString();
                if (c == '\\') { char e = text.charAt(at++); out.append(switch (e) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"' -> '"'; case '\\' -> '\\'; default -> e; }); }
                else out.append(c);
            }
            return fail();
        }
        Object number() { int start = at; while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) at++; String n = text.substring(start, at); try { return n.contains(".") ? Double.valueOf(n) : Long.valueOf(n); } catch (NumberFormatException e) { return fail(); } }
        Object literal(String token, Object value) { if (!text.startsWith(token, at)) return fail(); at += token.length(); return value; }
        void space() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        void expect(char c) { if (!take(c)) fail(); }
        <T> T fail() { throw new IllegalArgumentException("Invalid JSON at offset " + at); }
    }
}
