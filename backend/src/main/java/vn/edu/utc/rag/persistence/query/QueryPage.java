package vn.edu.utc.rag.persistence.query;

import java.util.*;

/** Immutable query inputs; repositories never receive a servlet request or session. */
public record QueryPage(String path, String caller, Map<String, String[]> parameters) {
  public QueryPage {
    Map<String, String[]> copy = new LinkedHashMap<>();
    parameters.forEach((k, v) -> copy.put(k, v.clone()));
    parameters = Collections.unmodifiableMap(copy);
  }

  public String getParameter(String name) {
    String[] values = parameters.get(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  public String getRequestURI() {
    return path;
  }

  public Map<String, String[]> getParameterMap() {
    Map<String, String[]> copy = new LinkedHashMap<>();
    parameters.forEach((k, v) -> copy.put(k, v.clone()));
    return Collections.unmodifiableMap(copy);
  }
}
