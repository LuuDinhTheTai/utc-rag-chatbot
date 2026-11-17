package vn.edu.utc.rag.persistence.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.common.*;

@Component
public class QueryPages {

  private final vn.edu.utc.rag.persistence.internal.NativeSqlExecutor db;

  private final JsonCodec json;

  private final byte[] secret;

  public QueryPages(
      vn.edu.utc.rag.persistence.internal.NativeSqlExecutor db,
      JsonCodec json,
      @Value("${app.cursor-secret}") String secret) {
    this.db = db;
    this.json = json;
    if (secret.length() < 32)
      throw new IllegalArgumentException("CURSOR_SECRET needs at least 32 characters");
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  private String signature(String text) {
    try {
      var m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> page(String sql, Map<String, ?> args, QueryPage req, String... order) {
    int limit;
    try {
      limit = req.getParameter("limit") == null ? 20 : Integer.parseInt(req.getParameter("limit"));
      if (limit < 1 || limit > 100) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new ApiException(400, "BAD_REQUEST", "limit must be between 1 and 100.");
    }
    Map<String, Object> params = new HashMap<>(args);
    var filters = new TreeMap<String, String>();
    req.getParameterMap()
        .forEach(
            (k, v) -> {
              if (!k.equals("cursor") && !k.equals("limit")) filters.put(k, String.join(",", v));
            });
    String scope = signature(req.getRequestURI() + "|" + req.caller() + "|" + json.encode(filters));
    String where = "";
    String cursor = req.getParameter("cursor");
    if (cursor != null) {
      try {
        if (cursor.length() > 4096) throw new IllegalArgumentException();
        var parts = cursor.split("\\.");
        if (parts.length != 2
            || !MessageDigest.isEqual(
                signature(parts[0]).getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
        var data = json.decode(Base64.getUrlDecoder().decode(parts[0]));
        if (!scope.equals(data.get("scope"))) throw new IllegalArgumentException();
        var keys = (List<?>) data.get("keys");
        if (keys.size() != order.length) throw new IllegalArgumentException();
        List<String> comparisons = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
          var key = order[i].split(":");
          params.put("cursor_" + i, keys.get(i));
          List<String> terms = new ArrayList<>();
          for (int j = 0; j < i; j++) {
            var prev = order[j].split(":");
            terms.add(prev[0] + "=CAST(:cursor_" + j + " AS " + prev[1] + ")");
          }
          terms.add(
              key[0]
                  + (key[2].equals("desc") ? "<" : ">")
                  + "CAST(:cursor_"
                  + i
                  + " AS "
                  + key[1]
                  + ")");
          comparisons.add("(" + String.join(" AND ", terms) + ")");
        }
        where = " WHERE " + String.join(" OR ", comparisons);
      } catch (Exception e) {
        throw new ApiException(400, "BAD_REQUEST", "Invalid cursor for this request.");
      }
    }
    String sorting =
        Arrays.stream(order)
            .map(
                s -> {
                  var p = s.split(":");
                  return p[0] + " " + p[2];
                })
            .collect(java.util.stream.Collectors.joining(","));
    params.put("page_limit", limit + 1);
    var rows =
        db.list(
            "SELECT * FROM ("
                + sql
                + ") page"
                + where
                + " ORDER BY "
                + sorting
                + " LIMIT :page_limit",
            params);
    boolean more = rows.size() > limit;
    if (more) rows = new ArrayList<>(rows.subList(0, limit));
    String next = null;
    if (more) {
      var last = rows.getLast();
      var data =
          Map.of(
              "scope",
              scope,
              "keys",
              Arrays.stream(order).map(s -> last.get(s.split(":")[0]).toString()).toList());
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(json.encode(data).getBytes(StandardCharsets.UTF_8));
      next = encoded + "." + signature(encoded);
    }
    Map<String, Object> pagination = new LinkedHashMap<>();
    pagination.put("next_cursor", next);
    pagination.put("has_more", more);
    return Map.of("data", rows, "pagination", pagination);
  }

  public static String filters(
      QueryPage req, Map<String, Object> params, Map<String, String> columns) {
    StringBuilder sql = new StringBuilder();
    columns.forEach(
        (name, column) -> {
          String v = req.getParameter(name);
          if (v != null && !v.isBlank()) {
            params.put("filter_" + name, v);
            sql.append(" AND CAST(").append(column).append(" AS text)=:filter_").append(name);
          }
        });
    return sql.toString();
  }
}
