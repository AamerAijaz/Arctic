package com.arctic.mcp.polarion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Test-step rows and column ids. Polarion's wire shape stays in this package. */
final class TestStepRows {

  record Row(List<String> keys, List<String> values) {}

  private TestStepRows() {}

  static List<Row> parse(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new PolarionException("Polarion test steps response was not a JSON object.");
    }
    JsonNode data = payload.get("data");
    if (data == null || !data.isArray()) {
      throw new PolarionException("Polarion test steps response had no data array.");
    }
    List<Row> rows = new ArrayList<>();
    for (JsonNode item : data) {
      rows.add(parseRow(item));
    }
    return List.copyOf(rows);
  }

  static List<String> columnsOf(List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<String> keys = rows.get(0).keys();
    for (Row row : rows) {
      if (!keys.equals(row.keys())) {
        throw new PolarionException("Polarion returned test steps with inconsistent columns.");
      }
    }
    return keys;
  }

  static List<String> columnIds(JsonNode enumeration) {
    if (enumeration == null || !enumeration.isObject()) {
      throw new PolarionException("Polarion returned an unexpected test step column response.");
    }
    JsonNode data = enumeration.get("data");
    JsonNode attributes = data == null || !data.isObject() ? null : data.get("attributes");
    JsonNode options =
        attributes == null || !attributes.isObject() ? null : attributes.get("options");
    if (options == null || !options.isArray()) {
      throw new PolarionException("Polarion returned an unexpected test step column response.");
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode option : options) {
      if (option == null || !option.isObject() || truth(option.get("hidden"))) {
        continue;
      }
      JsonNode id = option.get("id");
      if (id != null && id.isString() && !id.asString().isBlank()) {
        ids.add(id.asString());
      }
    }
    return List.copyOf(ids);
  }

  static List<Map<String, String>> toSteps(List<String> columns, List<Row> rows) {
    List<Map<String, String>> steps = new ArrayList<>();
    for (Row row : rows) {
      Map<String, String> step = new LinkedHashMap<>();
      for (int i = 0; i < columns.size(); i++) {
        step.put(columns.get(i), row.values().get(i));
      }
      steps.add(Map.copyOf(step));
    }
    return List.copyOf(steps);
  }

  static String version(List<String> columns, List<Map<String, String>> steps) {
    if (steps.isEmpty()) {
      return "empty";
    }
    StringBuilder canonical = new StringBuilder();
    for (String column : columns) {
      canonical.append(column).append('\u001f');
    }
    canonical.append('\u001e');
    for (Map<String, String> step : steps) {
      for (String column : columns) {
        canonical.append(step.get(column)).append('\u001f');
      }
      canonical.append('\u001e');
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static List<Map<String, String>> align(List<String> columns, List<Map<String, String>> steps) {
    List<Map<String, String>> aligned = new ArrayList<>();
    int index = 1;
    for (Map<String, String> step : steps) {
      if (step == null
          || !step.keySet().equals(Set.copyOf(columns))
          || step.values().stream().anyMatch(Objects::isNull)) {
        throw new PolarionException(
            "Test step " + index + " must use columns " + columns + " and no others.");
      }
      Map<String, String> ordered = new LinkedHashMap<>();
      for (String column : columns) {
        ordered.put(column, step.get(column));
      }
      aligned.add(Map.copyOf(ordered));
      index++;
    }
    return List.copyOf(aligned);
  }

  static Map<String, Object> postBody(List<String> columns, List<Map<String, String>> steps) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (Map<String, String> step : steps) {
      List<Map<String, String>> values = new ArrayList<>();
      for (String column : columns) {
        values.add(Map.of("type", "text/html", "value", step.get(column)));
      }
      data.add(
          Map.of("type", "teststeps", "attributes", Map.of("keys", columns, "values", values)));
    }
    return Map.of("data", data);
  }

  static boolean endsWith(List<Map<String, String>> current, List<Map<String, String>> addition) {
    if (addition.size() > current.size()) {
      return false;
    }
    int offset = current.size() - addition.size();
    for (int i = 0; i < addition.size(); i++) {
      if (!current.get(offset + i).equals(addition.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static Row parseRow(JsonNode item) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion test step was not a JSON object.");
    }
    JsonNode attributes = item.get("attributes");
    if (attributes == null || !attributes.isObject()) {
      throw new PolarionException("Polarion test step had no attributes object.");
    }
    List<String> keys = strings(attributes.get("keys"));
    List<String> values = textValues(attributes.get("values"));
    if (keys.size() != values.size()) {
      throw new PolarionException(
          "Polarion test step had " + keys.size() + " keys and " + values.size() + " values.");
    }
    return new Row(keys, values);
  }

  private static List<String> strings(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new PolarionException("Polarion test step keys were not an array.");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode child : node) {
      if (child == null || !child.isString()) {
        throw new PolarionException("Polarion test step key was not a string.");
      }
      values.add(child.asString());
    }
    return List.copyOf(values);
  }

  private static List<String> textValues(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new PolarionException("Polarion test step values were not an array.");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode child : node) {
      if (child == null || !child.isObject()) {
        throw new PolarionException("Polarion test step value was not a text object.");
      }
      JsonNode value = child.get("value");
      if (value == null || value.isNull() || value.isMissingNode()) {
        values.add("");
        continue;
      }
      if (!value.isString()) {
        throw new PolarionException("Polarion test step value was not text.");
      }
      values.add(value.asString());
    }
    return List.copyOf(values);
  }

  private static boolean truth(JsonNode node) {
    return node != null
        && (node.isBoolean() && node.asBoolean()
            || node.isString() && "true".equals(node.asString()));
  }
}
