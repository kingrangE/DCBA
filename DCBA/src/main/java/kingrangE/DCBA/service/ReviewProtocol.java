package kingrangE.DCBA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReviewProtocol {
    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ReviewProtocol() {}

    public record Criterion(String id, String label, String description) {}
    record Request(String question, String level, String modelAnswer, List<Criterion> criteria, String answer) {}
    public record CriterionFeedback(String id, String label, String status, String comment) {}
    public record Feedback(List<CriterionFeedback> criteria, String nextStep) {}

    public static String answer(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            requireFields(node, "answer");
            JsonNode value = node.get("answer");
            if (!value.isTextual()) throw new IllegalArgumentException();
            String answer = value.textValue().strip();
            if (answer.isEmpty() || answer.length() > 2000) throw new IllegalArgumentException();
            return answer;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("답변을 1자 이상 2,000자 이하로 입력해 주세요.");
        }
    }

    static List<Criterion> rubric(String value) {
        if (value == null) return List.of();
        try {
            JsonNode criteria = JSON.readTree(value);
            if (!criteria.isArray() || criteria.size() < 2 || criteria.size() > 4) return List.of();
            Set<String> ids = new HashSet<>();
            List<Criterion> result = new ArrayList<>();
            for (JsonNode criterion : criteria) {
                requireFields(criterion, "id", "label", "description");
                String id = text(criterion, "id", 32);
                if (!id.matches("[a-z0-9_]{1,32}") || !ids.add(id)) return List.of();
                result.add(new Criterion(id, text(criterion, "label", 80), text(criterion, "description", 400)));
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            return List.of();
        }
    }

    static Feedback feedback(String value, List<Criterion> rubric) throws JsonProcessingException {
        JsonNode root = JSON.readTree(value);
        requireFields(root, "criteria", "nextStep");
        JsonNode items = root.get("criteria");
        if (!items.isArray() || items.size() != rubric.size()) throw new IllegalArgumentException();
        var byId = new HashMap<String, CriterionFeedback>();
        for (JsonNode item : items) {
            requireFields(item, "id", "status", "comment");
            String id = text(item, "id", 32);
            String status = text(item, "status", 7);
            if (!Set.of("met", "partial", "missing").contains(status)
                    || byId.putIfAbsent(id, new CriterionFeedback(id, null, status, text(item, "comment", 240))) != null) {
                throw new IllegalArgumentException();
            }
        }
        List<CriterionFeedback> result = new ArrayList<>();
        for (Criterion criterion : rubric) {
            var match = byId.get(criterion.id());
            if (match == null) throw new IllegalArgumentException();
            result.add(new CriterionFeedback(criterion.id(), criterion.label(), match.status(), match.comment()));
        }
        return new Feedback(result, text(root, "nextStep", 240));
    }

    private static void requireFields(JsonNode object, String... fields) {
        if (object == null || !object.isObject() || object.size() != fields.length) throw new IllegalArgumentException();
        for (String field : fields) if (!object.has(field)) throw new IllegalArgumentException();
    }

    private static String text(JsonNode object, String field, int max) {
        JsonNode node = object.get(field);
        if (!node.isTextual()) throw new IllegalArgumentException();
        String value = node.textValue();
        if (value.isBlank() || value.codePointCount(0, value.length()) > max) throw new IllegalArgumentException();
        return value;
    }
}
