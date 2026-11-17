package vn.edu.utc.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private final Path source = Path.of("src/main/java/vn/edu/utc/rag");

  @Test
  void layersKeepHttpAndInfrastructureOutOfBusinessServices() throws Exception {
    try (var files = Files.walk(source)) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String code = Files.readString(file);
        String name = file.getFileName().toString();
        if (name.endsWith("Controller.java")) {
          assertThat(file.startsWith(source.resolve("controller"))).as(name).isTrue();
          assertThat(code)
              .as(name)
              .doesNotContain("persistence.repository", "integration.", "org.apache.pdfbox");
        }
        if (name.endsWith("Service.java")) {
          assertThat(file.startsWith(source.resolve("service"))).as(name).isTrue();
          assertThat(code)
              .as(name)
              .doesNotContain(
                  "org.springframework.http",
                  "ResponseEntity",
                  "web.bind.annotation",
                  "vn.edu.utc.rag.controller.",
                  "org.apache.pdfbox");
        }
        if (file.startsWith(source.resolve("persistence"))) {
          assertThat(code)
              .as(name)
              .doesNotContain("vn.edu.utc.rag.controller.", "vn.edu.utc.rag.service.");
        }
      }
    }
  }

  @Test
  void repositoriesAreCentralizedAndMappersCannotAccessPersistence() throws Exception {
    try (var files = Files.walk(source)) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String code = Files.readString(file);
        if (file.getFileName().toString().endsWith("Repository.java")) {
          assertThat(file.normalize().startsWith(source.resolve("persistence")))
              .as(file.toString())
              .isTrue();
        }
        if (file.startsWith(source.resolve("mapper"))) {
          assertThat(code)
              .as(file.toString())
              .doesNotContain(
                  "persistence.repository", "NativeSqlExecutor", "EntityManager", "@Transactional");
        }
      }
    }
  }

  @Test
  void simpleCrudUsesDerivedQueriesAndLocksApplyToActualQueries() throws Exception {
    try (var files = Files.list(source.resolve("persistence/repository"))) {
      for (var file : files.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
        String name = file.getFileName().toString().replace(".java", "");
        Class<?> type = Class.forName("vn.edu.utc.rag.persistence.repository." + name);
        if (!type.isInterface()) continue;
        for (var method : type.getDeclaredMethods()) {
          var lock = method.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
          if (lock != null) assertThat(method.isDefault()).as(method.toString()).isFalse();
          var query = method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
          if (query == null) continue;
          assertThat(query.value())
              .as(method.toString())
              .doesNotMatch("(?is)UPDATE\\s+.*")
              .doesNotMatch(
                  "(?is)SELECT (?:e|count\\(e\\)) FROM \\w+ e WHERE e\\.\\w+=:\\w+(?: ORDER BY"
                      + " .*)?");
          // The self-referencing message graph must be deleted in one statement.
          boolean messageGraphDelete =
              type.getSimpleName().equals("MessagesRepository")
                  && method.getName().equals("deleteByConversationId");
          if (!messageGraphDelete) {
            assertThat(query.value())
                .as(method.toString())
                .doesNotMatch("(?is)DELETE FROM \\w+ e WHERE e\\.\\w+=:\\w+");
          }
        }
      }
    }
  }

  @Test
  void springDataRepositoriesUseTypedParameters() throws Exception {
    try (var files = Files.list(source.resolve("persistence/repository"))) {
      for (var file : files.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
        String name = file.getFileName().toString().replace(".java", "");
        Class<?> type = Class.forName("vn.edu.utc.rag.persistence.repository." + name);
        if (!type.isInterface()) continue;
        for (var method : type.getDeclaredMethods()) {
          assertThat(method.getParameterTypes())
              .as(type.getSimpleName() + "." + method.getName())
              .noneMatch(java.util.Map.class::isAssignableFrom);
        }
        assertThat(Files.readString(file))
            .doesNotContain("#parameters[", "queryAdminDataRepository");
      }
    }
  }

  @Test
  void servicesAndControllersCannotExecuteSql() throws Exception {
    try (var files = Files.walk(source)) {
      for (var file :
          files.filter(p -> p.toString().matches(".*(Service|Controller)\\.java$")).toList()) {
        String code = Files.readString(file);
        assertThat(code)
            .as(file.toString())
            .doesNotContain("NativeSqlExecutor", "EntityManager", "createNativeQuery")
            .doesNotContainPattern("(?i)\"(?:SELECT|INSERT INTO|UPDATE|DELETE FROM)\\s");
      }
    }
    assertThat(source.resolve("common/Db.java")).doesNotExist();
    assertThat(source.resolve("persistence/JpaStore.java")).doesNotExist();
  }

  @Test
  void repositoriesDoNotDependOnWebRequestsOrServices() throws Exception {
    try (var files = Files.walk(source)) {
      for (var file : files.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
        assertThat(Files.readString(file))
            .as(file.toString())
            .doesNotContain(
                "jakarta.servlet", "HttpServletRequest", "HttpSession", "EditorialService.");
      }
    }
  }
}
