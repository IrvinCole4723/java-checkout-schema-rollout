import java.util.List;
import java.util.Map;

public final class JsonTest {
    public static void main(String[] args) {
        Map<?, ?> envelope = (Map<?, ?>) Json.parse(
                "{\"ok\":true,\"data\":{\"items\":[]},\"metadata\":{\"values\":[1,\"two\",true,null,[{}]]}}");
        if (!List.of().equals(((Map<?, ?>) envelope.get("data")).get("items")))
            throw new AssertionError("Empty array was not decoded");
        List<?> values = (List<?>) ((Map<?, ?>) envelope.get("metadata")).get("values");
        if (values.size() != 5 || !values.get(1).equals("two") || values.get(3) != null
                || !((List<?>) values.get(4)).get(0).equals(Map.of()))
            throw new AssertionError("Nested mixed array was not decoded");
        for (String invalid : List.of("[1,]", "[1", "[,1]", "[1 2]")) {
            try { Json.parse(invalid); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Accepted malformed array: " + invalid);
        }
        System.out.println("PASS: JSON response arrays");
    }
}
