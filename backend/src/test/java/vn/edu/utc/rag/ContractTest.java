package vn.edu.utc.rag;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.administration.AdminController;
import vn.edu.utc.rag.catalog.CatalogController;
import vn.edu.utc.rag.chat.ChatController;
import vn.edu.utc.rag.editorial.EditorialController;
import vn.edu.utc.rag.editorial.EditorialService;
import vn.edu.utc.rag.identity.IdentityController;
import vn.edu.utc.rag.ingestion.DocumentsController;
import vn.edu.utc.rag.ingestion.OpsController;
import vn.edu.utc.rag.personality.PersonalityController;
import vn.edu.utc.rag.scoring.ScoringController;

class ContractTest {
  String normalize(String path) {
    return path.replaceAll("\\{[^}]+}", "{}");
  }

  @Test
  void all112OperationsHaveControllersAndAContract() throws Exception {
    JsonNode spec;
    try (var in = getClass().getResourceAsStream("/static/openapi.json")) {
      spec = new ObjectMapper().readTree(in);
    }
    Set<String> expected = new TreeSet<>();
    spec.path("paths")
        .fields()
        .forEachRemaining(
            p ->
                p.getValue()
                    .fieldNames()
                    .forEachRemaining(
                        method ->
                            expected.add(
                                method.toUpperCase() + " " + normalize("/api/v1" + p.getKey()))));
    assertThat(spec.path("paths").size()).isEqualTo(84);
    assertThat(expected).hasSize(112);
    Set<String> actual = new TreeSet<>();
    for (Class<?> controller :
        List.of(
            IdentityController.class,
            CatalogController.class,
            ChatController.class,
            PersonalityController.class,
            ScoringController.class,
            EditorialController.class,
            DocumentsController.class,
            OpsController.class,
            AdminController.class)) {
      String root = controller.getAnnotation(RequestMapping.class).value()[0];
      List<String> roots =
          controller == EditorialController.class
              ? EditorialService.KINDS.keySet().stream().map(k -> "/api/v1/editorial/" + k).toList()
              : List.of(root);
      for (var method : controller.getDeclaredMethods()) {
        String verb = null;
        String[] suffix = null;
        if (method.isAnnotationPresent(GetMapping.class)) {
          verb = "GET";
          suffix = method.getAnnotation(GetMapping.class).value();
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
          verb = "POST";
          suffix = method.getAnnotation(PostMapping.class).value();
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
          verb = "PUT";
          suffix = method.getAnnotation(PutMapping.class).value();
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
          verb = "PATCH";
          suffix = method.getAnnotation(PatchMapping.class).value();
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
          verb = "DELETE";
          suffix = method.getAnnotation(DeleteMapping.class).value();
        }
        if (verb == null) continue;
        if (suffix.length == 0) suffix = new String[] {""};
        for (String r : roots) for (String s : suffix) actual.add(verb + " " + normalize(r + s));
      }
    }
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void idAndDecimalParsingDoesNotLosePrecision() {
    assertThat(vn.edu.utc.rag.common.Input.id("9007199254740993")).isEqualTo(9007199254740993L);
    assertThat(vn.edu.utc.rag.common.Input.decimal("8.50").toPlainString()).isEqualTo("8.50");
    assertThatThrownBy(() -> vn.edu.utc.rag.common.Input.id(9007199254740993L))
        .isInstanceOf(vn.edu.utc.rag.common.ApiException.class);
  }
}
