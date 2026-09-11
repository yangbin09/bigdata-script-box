package com.bigdata.scriptbox.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * V2 conditional parameter rule. A small JSON object on {@code ScriptParam.visibleWhenJson}:
 * <pre>
 *   { "param": "env", "operator": "equals", "value": "prod" }
 * </pre>
 * Supported operators: {@code equals}, {@code notEquals}. Anything else is treated
 * as always-visible. Null/blank json is also always-visible (represented by a
 * null {@code VisibleWhen} reference).
 *
 * <p>Decisions are evaluated against the candidate map of resolved parameter values
 * (string form). When the referenced param is missing or blank, the rule is considered
 * NOT matching (so dependents stay hidden — we never silently fall back to defaults).
 */
public final class VisibleWhen {

    public static final String OP_EQUALS = "equals";
    public static final String OP_NOT_EQUALS = "notEquals";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String param;
    private final String operator;
    private final String value;

    private VisibleWhen(String param, String operator, String value) {
        this.param = param;
        this.operator = operator == null ? OP_EQUALS : operator;
        this.value = value;
    }

    public String getParam() { return param; }
    public String getOperator() { return operator; }
    public String getValue() { return value; }

    /** Parse the JSON; returns null when the input is null/blank/malformed. */
    public static VisibleWhen parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) return null;
            String p = textOrNull(node, "param");
            String op = textOrNull(node, "operator");
            String v = textOrNull(node, "value");
            if (p == null || op == null) return null;
            String norm = op.trim();
            if (!OP_EQUALS.equalsIgnoreCase(norm) && !OP_NOT_EQUALS.equalsIgnoreCase(norm)) return null;
            return new VisibleWhen(p.trim(), norm, v == null ? "" : v);
        } catch (Exception ex) {
            return null;  // malformed ⇒ don't hide
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        return n.asText();
    }

    /**
     * @return true when this rule's condition is satisfied by the given values.
     *         A null {@code VisibleWhen} always returns true.
     */
    public static boolean isVisible(VisibleWhen rule, Map<String, String> values) {
        if (rule == null) return true;
        String current = values == null ? null : values.get(rule.param);
        boolean eq = (current == null ? "" : current).equals(rule.value == null ? "" : rule.value);
        return OP_EQUALS.equalsIgnoreCase(rule.operator) ? eq : !eq;
    }

    /** Instance convenience. */
    public boolean matches(Map<String, String> values) { return isVisible(this, values); }

    @Override
    public String toString() {
        return "VisibleWhen{param=" + param + ", op=" + operator + ", value=" + value + "}";
    }
}
