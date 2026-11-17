package vn.edu.utc.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.integration.ollama.OllamaClient;
import vn.edu.utc.rag.security.Access;
import vn.edu.utc.rag.validation.Input;

/**
 * Real PostgreSQL tests. The runner creates a disposable database, never the development database.
 */
@SpringBootTest(properties = {"app.upload-directory=/tmp/utc-rag-api-test-uploads"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "UTC_RUN_DB_TESTS", matches = "true")
class BackendIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired vn.edu.utc.rag.persistence.internal.NativeSqlExecutor db;
  @MockitoBean OllamaClient ai;
  MockHttpSession staff;
  MockHttpSession student;
  String campus,
      otherCampus,
      field,
      program,
      offering,
      method,
      combination,
      course,
      document,
      revision;
  int sequence = 0;

  String id(String sql, Object... pairs) {
    var args = new HashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) args.put(pairs[i].toString(), pairs[i + 1]);
    return db.one(sql, args).get("id").toString();
  }

  @Autowired vn.edu.utc.rag.persistence.repository.CampusesRepository campusRepository;

  @Autowired
  vn.edu.utc.rag.persistence.repository.CombinationSubjectsRepository combinationRepository;

  @Autowired vn.edu.utc.rag.persistence.repository.DocumentChunksRepository chunkRepository;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Test
  void jpaMappingsCompositeKeysAndVectorRoundTrip() {
    assertThat(entityManager.getMetamodel().getEntities()).hasSize(48);
    var campusEntity = new vn.edu.utc.rag.persistence.entity.CampusesEntity();
    campusEntity.setCode("JPA_TEST");
    campusEntity.setName("JPA campus");
    campusRepository.saveAndFlush(campusEntity);
    assertThat(campusRepository.findById(campusEntity.getId())).isPresent();
    var subject = new vn.edu.utc.rag.persistence.entity.CombinationSubjectsEntity();
    subject.setCombinationId(Short.valueOf(combination));
    subject.setSubjectCode("JPA_MATH");
    subject.setPosition((short) 1);
    combinationRepository.saveAndFlush(subject);
    var key = new vn.edu.utc.rag.persistence.entity.CombinationSubjectsEntityId();
    key.combinationId = Short.valueOf(combination);
    key.subjectCode = "JPA_MATH";
    assertThat(combinationRepository.findById(key)).isPresent();
    var chunk = new vn.edu.utc.rag.persistence.entity.DocumentChunksEntity();
    chunk.setRevisionId(Long.valueOf(revision));
    chunk.setPosition(9);
    chunk.setBody("JPA vector");
    var vector = new float[768];
    vector[0] = 1;
    chunk.setEmbedding(vector);
    chunkRepository.saveAndFlush(chunk);
    entityManager.clear();
    assertThat(chunkRepository.findById(chunk.getId()).orElseThrow().getEmbedding())
        .containsExactly(vector);
    db.update(
        "UPDATE campuses SET name='native change' WHERE id=:id",
        Map.of("id", campusEntity.getId()));
    assertThat(campusRepository.findById(campusEntity.getId()).orElseThrow().getName())
        .isEqualTo("native change");
  }

  @BeforeEach
  void fixtures() {
    assertThat(System.getenv("SPRING_DATASOURCE_URL")).contains("backend_test_");
    staff = account("ADMIN", "STAFF_EDITOR", "STAFF_REVIEWER", "TECH_OPERATOR");
    student = account("STUDENT");
    campus = id("INSERT INTO campuses(code,name) VALUES('TEST_HN','Hà Nội') RETURNING id");
    otherCampus =
        id("INSERT INTO campuses(code,name) VALUES('TEST_HCM','Hồ Chí Minh') RETURNING id");
    field =
        id(
            "INSERT INTO academic_fields(code,name) VALUES('TEST_FIELD','Engineering') RETURNING"
                + " id");
    program =
        id(
            "INSERT INTO programs(field_id,code,name) VALUES(:field,'TEST_PROGRAM','Công nghệ thông"
                + " tin') RETURNING id",
            "field",
            Input.id(field));
    offering =
        id(
            "INSERT INTO program_offerings(program_id,campus_id,entry_year) VALUES(:p,:c,2026)"
                + " RETURNING id",
            "p",
            Input.id(program),
            "c",
            Input.id(campus));
    method = id("INSERT INTO admission_methods(code,name) VALUES('TEST_THPT','THPT') RETURNING id");
    combination = id("INSERT INTO subject_combinations(code) VALUES('TEST_A00') RETURNING id");
    course = id("INSERT INTO courses(code) VALUES('TEST_CS101') RETURNING id");
    document =
        id(
            "INSERT INTO knowledge_documents(canonical_url)"
                + " VALUES('https://tuyensinh.utc.edu.vn/test-api') RETURNING id");
    revision =
        id(
            "INSERT INTO"
                + " document_revisions(document_id,campus_id,admission_year,title,official_url,content_hash,pipeline_key,embedding_model,status,updated_at)"
                + " VALUES(:d,:c,2026,'Thông báo kiểm"
                + " thử','https://tuyensinh.utc.edu.vn/test-api','hash','test','embeddinggemma:300m-qat-q4_0','PENDING',now())"
                + " RETURNING id",
            "d",
            Input.id(document),
            "c",
            Input.id(campus));
    String vector = "[1," + "0,".repeat(766) + "0]";
    db.update(
        "INSERT INTO document_chunks(revision_id,position,body,embedding) VALUES(:r,1,'Tài liệu"
            + " tuyển sinh UTC được kiểm thử.',CAST(:v AS vector))",
        Map.of("r", Input.id(revision), "v", vector));
    when(ai.embed(anyString())).thenReturn(vector);
    when(ai.answer(anyString(), anyString())).thenReturn("Câu trả lời từ tài liệu được cung cấp.");
  }

  MockHttpSession account(String... roles) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO users(id,email,password_hash,status,auth_epoch,created_at)"
            + " VALUES(:u,:e,'not-used','ACTIVE',0,now())",
        Map.of("u", id, "e", id + "@test.invalid"));
    for (String role : roles)
      db.update(
          "INSERT INTO role_assignments(user_id,role_code,assigned_at) VALUES(:u,:r,now())",
          Map.of("u", id, "r", role));
    var s = new MockHttpSession();
    s.setAttribute("account", new Access.Account(id, 0));
    return s;
  }

  JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
    var response =
        mvc.perform(request.with(csrf().asHeader()))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse();
    return response.getContentAsByteArray().length == 0
        ? json.nullNode()
        : json.readTree(response.getContentAsByteArray());
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req, Object b) {
    try {
      return req.contentType("application/json").content(json.writeValueAsBytes(b));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  JsonNode post(String path, Object b, MockHttpSession s, int status) throws Exception {
    return call(body(MockMvcRequestBuilders.post("/api/v1" + path).session(s), b), status);
  }

  JsonNode put(String path, Object b, MockHttpSession s, String etag, int status) throws Exception {
    var r = body(MockMvcRequestBuilders.put("/api/v1" + path).session(s), b);
    if (etag != null) r.header("If-Match", etag);
    return call(r, status);
  }

  JsonNode get(String path, MockHttpSession s, int status) throws Exception {
    return call(MockMvcRequestBuilders.get("/api/v1" + path).session(s), status);
  }

  void publishSource() throws Exception {
    put(
        "/editorial/documents/" + document + "/publication",
        Map.of("version_id", revision),
        staff,
        "\"publication-0\"",
        200);
  }

  String createCycle() throws Exception {
    return post(
            "/editorial/admission-cycles",
            Map.of("campus_code", "TEST_HN", "year", 2026, "round_code", "TEST_ROUND"),
            staff,
            201)
        .get("id")
        .asText();
  }

  Map<String, Object> draft(String kind) {
    return switch (kind) {
      case "admission-cycles" ->
          Map.of(
              "source_revision_id",
              revision,
              "methods",
              List.of(Map.of("method_id", method, "eligibility_text", "Điều kiện kiểm thử")),
              "choices",
              List.of(
                  Map.of(
                      "program_offering_id",
                      offering,
                      "total_quota",
                      10,
                      "options",
                      List.of(
                          Map.of(
                              "method_id",
                              method,
                              "method_quota",
                              10,
                              "combinations",
                              List.of(Map.of("combination_id", combination)),
                              "cutoffs",
                              List.of(
                                  Map.of(
                                      "score",
                                      "22.50",
                                      "scale_code",
                                      "SCALE_30",
                                      "source_revision_id",
                                      revision,
                                      "combination_id",
                                      combination)))))),
              "schedule_events",
              List.of(Map.of("title", "Mốc kiểm thử", "starts_at", "2026-01-01T00:00:00Z")));
      case "program-offerings" ->
          Map.of(
              "source_revision_id",
              revision,
              "duration_months",
              48,
              "total_credits",
              120,
              "objectives",
              "Mục tiêu",
              "learning_outcomes",
              "Chuẩn đầu ra",
              "career_paths",
              "Nghề nghiệp",
              "courses",
              List.of(
                  Map.of(
                      "course_id",
                      course,
                      "course_title",
                      "Lập trình",
                      "credits",
                      3,
                      "course_group",
                      "CORE",
                      "suggested_term",
                      1)));
      case "guide-items" ->
          Map.of(
              "source_revision_id",
              revision,
              "title",
              "Hướng dẫn",
              "body",
              "Nội dung",
              "entries",
              List.of(
                  Map.of(
                      "position",
                      1,
                      "entry_type",
                      "CHECKLIST",
                      "label",
                      "Hồ sơ",
                      "is_required",
                      true)));
      case "financial-policies" ->
          Map.of(
              "source_revision_id",
              revision,
              "title",
              "Học phí",
              "body",
              "Nội dung",
              "amount_vnd",
              "1000000",
              "charge_unit",
              "TERM");
      case "score-rules" ->
          Map.of(
              "source_revision_id",
              revision,
              "formula_key",
              "SUM",
              "formula_config",
              Map.of("decimal_places", 2),
              "output_scale_code",
              "SCALE_30",
              "inputs",
              List.of(
                  Map.of(
                      "input_kind",
                      "NUMERIC",
                      "input_code",
                      "MATH",
                      "label",
                      "Toán",
                      "is_required",
                      true,
                      "min_value",
                      "0",
                      "max_value",
                      "10")));
      default -> throw new IllegalArgumentException();
    };
  }

  @Test
  void authenticationCsrfAndSessionRotation() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/auth/accounts")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var csrfResult =
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
    var session = (MockHttpSession) csrfResult.getRequest().getSession();
    String old = session.getId();
    var credentials =
        Map.of("email", UUID.randomUUID() + "@example.invalid", "password", "test-secret-password");
    var account = post("/auth/accounts", credentials, session, 201);
    assertThat(session.getId()).isNotEqualTo(old);
    assertThat(account.has("password_hash")).isFalse();
    get("/me", session, 200);
    call(MockMvcRequestBuilders.delete("/api/v1/auth/sessions/current").session(session), 204);
    var fresh = new MockHttpSession();
    get("/auth/csrf", fresh, 200);
    post("/auth/sessions", credentials, fresh, 200);
    get("/me", fresh, 200);
    post(
        "/auth/sessions",
        Map.of("email", credentials.get("email"), "password", "wrong-password"),
        new MockHttpSession(),
        401);
  }

  @Test
  void allPublicListsAndInvalidLimits() throws Exception {
    for (String path :
        List.of(
            "/campuses",
            "/chat-suggestions",
            "/admission-cycles",
            "/admission-cutoffs",
            "/programs",
            "/program-offerings",
            "/questionnaires",
            "/score-rules",
            "/guides",
            "/schedule-events",
            "/financial-policies")) get(path, new MockHttpSession(), 200);
    get("/campuses?limit=101", student, 400);
    get("/campuses?cursor=bad", student, 400);
    get("/me", new MockHttpSession(), 401);
    get("/admin/accounts", student, 403);
  }

  @Test
  void cursorIsBoundToFilterAndCaller() throws Exception {
    var first = get("/campuses?limit=1", student, 200);
    String cursor = first.path("pagination").path("next_cursor").asText();
    assertThat(first.path("pagination").path("has_more").asBoolean()).isTrue();
    var second = get("/campuses?limit=1&cursor=" + cursor, student, 200);
    assertThat(second.path("data").get(0).path("id").asText())
        .isNotEqualTo(first.path("data").get(0).path("id").asText());
    get("/campuses?limit=1&q=changed&cursor=" + cursor, student, 400);
    get("/campuses?limit=1&cursor=" + cursor, staff, 400);
  }

  @Test
  void privateOwnershipAndChatRetry() throws Exception {
    String c = post("/conversations", Map.of(), student, 201).get("id").asText();
    String msg = UUID.randomUUID().toString();
    get("/conversations/" + c, staff, 404);
    get("/conversations/" + c, student, 200);
    var turn =
        post(
            "/conversations/" + c + "/messages",
            Map.of("client_message_id", msg, "content", "Tuyển sinh?"),
            student,
            201);
    assertThat(turn.path("assistant_message").path("answer_status").asText())
        .isEqualTo("INSUFFICIENT_EVIDENCE");
    post(
        "/conversations/" + c + "/messages",
        Map.of("client_message_id", msg, "content", "Tuyển sinh?"),
        student,
        200);
    post(
        "/conversations/" + c + "/messages",
        Map.of("client_message_id", msg, "content", "Khác"),
        student,
        409);
    var messages = get("/conversations/" + c + "/messages", student, 200);
    assertThat(messages.path("data").size()).isEqualTo(2);
    String answer = turn.path("assistant_message").path("id").asText();
    get("/conversations/" + c + "/messages/" + answer, student, 200);
    put(
        "/conversations/" + c + "/messages/" + answer + "/feedback",
        Map.of("is_helpful", true),
        student,
        null,
        204);
    get("/me/conversations?q=Tuyển", student, 200);
    call(MockMvcRequestBuilders.delete("/api/v1/conversations/" + c).session(student), 204);
  }

  @Test
  void groundedChatAndAiFailureAreAtomic() throws Exception {
    publishSource();
    String c = post("/conversations", Map.of(), student, 201).get("id").asText();
    var turn =
        post(
            "/conversations/" + c + "/messages",
            Map.of("client_message_id", UUID.randomUUID().toString(), "content", "Tuyển sinh?"),
            student,
            201);
    assertThat(turn.path("assistant_message").path("citations").size()).isEqualTo(1);
    when(ai.answer(anyString(), anyString()))
        .thenThrow(new ApiException(504, "UPSTREAM_TIMEOUT", "Timeout."));
    post(
        "/conversations/" + c + "/messages",
        Map.of("client_message_id", UUID.randomUUID().toString(), "content", "Năm nay?"),
        student,
        504);
    assertThat(get("/conversations/" + c + "/messages", student, 200).path("data").size())
        .isEqualTo(2);
  }

  @Test
  void fiveEditorialFamiliesPublicationAndRevocation() throws Exception {
    publishSource();
    Map<String, String> roots = new LinkedHashMap<>();
    roots.put("admission-cycles", createCycle());
    roots.put("program-offerings", offering);
    roots.put(
        "guide-items",
        post(
                "/editorial/guide-items",
                Map.of(
                    "campus_code",
                    "TEST_HN",
                    "year",
                    2026,
                    "guide_type",
                    "ENROLLMENT",
                    "audience_code",
                    "ALL",
                    "guide_slug",
                    "test-guide"),
                staff,
                201)
            .get("id")
            .asText());
    roots.put(
        "financial-policies",
        post(
                "/editorial/financial-policies",
                Map.of(
                    "campus_code",
                    "TEST_HN",
                    "entry_year",
                    2026,
                    "policy_type",
                    "TUITION",
                    "policy_code",
                    "TEST_FEE"),
                staff,
                201)
            .get("id")
            .asText());
    roots.put(
        "score-rules",
        post(
                "/editorial/score-rules",
                Map.of("campus_code", "TEST_HN", "year", 2026, "method_id", method),
                staff,
                201)
            .get("id")
            .asText());
    for (var root : roots.entrySet()) {
      String path = "/editorial/" + root.getKey() + "/" + root.getValue();
      get("/editorial/" + root.getKey(), staff, 200);
      get(path, staff, 200);
      get(path + "/publication", staff, 200);
      String v = post(path + "/versions", draft(root.getKey()), staff, 201).get("id").asText();
      get(path + "/versions", staff, 200);
      get(path + "/versions/" + v, staff, 200);
      put(path + "/versions/" + v, draft(root.getKey()), staff, null, 428);
      put(path + "/versions/" + v, draft(root.getKey()), staff, "\"version-0\"", 200);
      put(path + "/versions/" + v, draft(root.getKey()), staff, "\"version-0\"", 412);
      put(path + "/publication", Map.of("version_id", v), staff, "\"publication-0\"", 200);
      // Flush and reload to verify dirty checking rather than the managed entity's state.
      entityManager.flush();
      entityManager.clear();
      assertThat(get(path + "/versions/" + v, staff, 200).path("status").asText())
          .isEqualTo("PUBLISHED");
      String replacement =
          post(path + "/versions", draft(root.getKey()), staff, 201).get("id").asText();
      put(
          path + "/publication",
          Map.of("version_id", replacement),
          staff,
          "\"publication-1\"",
          200);
      entityManager.flush();
      entityManager.clear();
      assertThat(get(path + "/versions/" + v, staff, 200).path("status").asText())
          .isEqualTo("ARCHIVED");
      assertThat(get(path + "/versions/" + replacement, staff, 200).path("status").asText())
          .isEqualTo("PUBLISHED");
    }
    var cycles = get("/admission-cycles?campus_code=TEST_HN&year=2026", student, 200);
    String cycle = roots.get("admission-cycles");
    assertThat(cycles.path("data").size()).isEqualTo(1);
    get("/admission-cycles/" + cycle, student, 200);
    get("/admission-cycles/" + cycle + "/methods", student, 200);
    var options = get("/admission-cycles/" + cycle + "/options", student, 200);
    String option = options.path("data").get(0).path("id").asText();
    get("/admission-options/" + option + "/subject-combinations", student, 200);
    get("/admission-cutoffs?combination_code=TEST_A00", student, 200);
    get("/programs/" + program, student, 200);
    get("/programs?campus_code=TEST_HN&year=2026", student, 200);
    get("/program-offerings/" + offering + "/curriculum", student, 200);
    get("/program-offerings/" + offering + "/courses", student, 200);
    get("/guides/" + roots.get("guide-items"), student, 200);
    get("/guides/" + roots.get("guide-items") + "/entries", student, 200);
    get("/schedule-events?campus_code=TEST_HCM", student, 200).path("data").isEmpty();
    get("/financial-policies/" + roots.get("financial-policies"), student, 200);
    var form = get("/score-rules/" + roots.get("score-rules") + "/form", student, 200);
    var score =
        post(
            "/score-calculations",
            Map.of(
                "rule_version_id",
                form.get("version_id").asText(),
                "inputs",
                List.of(Map.of("input_code", "MATH", "numeric_value", "8.50"))),
            student,
            200);
    assertThat(score.get("total").asText()).isEqualTo("8.50");
    post(
        "/score-calculations",
        Map.of(
            "rule_version_id",
            form.get("version_id").asText(),
            "inputs",
            List.of(Map.of("input_code", "MATH", "numeric_value", "11"))),
        student,
        422);
    put(
        "/editorial/document-revisions/" + revision + "/revocation",
        Map.of("reason", "Nguồn không còn hiệu lực"),
        staff,
        null,
        200);
    for (String path :
        List.of(
            "/admission-cycles",
            "/program-offerings",
            "/guides",
            "/financial-policies",
            "/score-rules")) assertThat(get(path, student, 200).path("data").size()).isZero();
    get("/program-offerings/" + offering + "/curriculum", student, 404);
  }

  @Test
  void campusIsolationAndRoleSeparation() throws Exception {
    var editor = account("STUDENT");
    UUID id = ((Access.Account) editor.getAttribute("account")).id();
    db.update(
        "INSERT INTO role_assignments(user_id,role_code,campus_id,assigned_at)"
            + " VALUES(:u,'STAFF_EDITOR',:c,now())",
        Map.of("u", id, "c", Input.id(otherCampus)));
    String c = createCycle();
    get("/editorial/admission-cycles/" + c, editor, 404);
    put(
        "/editorial/admission-cycles/" + c + "/publication",
        Map.of("version_id", revision),
        editor,
        "\"publication-0\"",
        403);
    var r =
        post(
            "/editorial/admission-cycles",
            Map.of("campus_code", "TEST_HCM", "year", 2026, "round_code", "TEST_OTHER"),
            editor,
            201);
    get("/editorial/admission-cycles/" + r.get("id").asText(), editor, 200);
    assertThat(get("/editorial/admission-cycles", editor, 200).path("data").size()).isEqualTo(1);
  }

  @Test
  void questionnaireIsPinnedAndAnswersAreValidated() throws Exception {
    String q = id("INSERT INTO questionnaires(code,name) VALUES('TEST_Q','Khám phá') RETURNING id");
    String v =
        id(
            "INSERT INTO"
                + " questionnaire_versions(questionnaire_id,version_no,status,instructions,scoring_config)"
                + " VALUES(:q,1,'PUBLISHED','Hướng dẫn',CAST(:c AS jsonb)) RETURNING id",
            "q",
            Input.id(q),
            "c",
            db.encode(
                Map.of(
                    "strategy",
                    "WEIGHTED_SUM",
                    "profiles",
                    Map.of("TEST_PROFILE", Map.of("logic", "1")))));
    String question =
        id(
            "INSERT INTO personality_questions(questionnaire_version_id,position,body)"
                + " VALUES(:v,1,'Câu hỏi') RETURNING id",
            "v",
            Input.id(v));
    String option =
        id(
            "INSERT INTO personality_options(question_id,position,label,score_weights)"
                + " VALUES(:q,1,'Đồng ý','{\"logic\":\"2\"}') RETURNING id",
            "q",
            Input.id(question));
    id(
        "INSERT INTO personality_profiles(questionnaire_version_id,profile_code,name,description)"
            + " VALUES(:v,'TEST_PROFILE','Phân tích','Mô tả tham khảo') RETURNING id",
        "v",
        Input.id(v));
    get("/questionnaires", student, 200);
    get("/questionnaires/" + q + "/questions?version_id=" + v, student, 200);
    String a =
        post("/personality-attempts", Map.of("questionnaire_version_id", v), student, 201)
            .get("id")
            .asText();
    db.update(
        "UPDATE questionnaire_versions SET status='ARCHIVED' WHERE id=:v",
        Map.of("v", Input.id(v)));
    get("/personality-attempts/" + a, student, 200);
    get("/personality-attempts/" + a + "/questions", student, 200);
    get("/personality-attempts/" + a, staff, 404);
    post("/personality-attempts/" + a + "/result", Map.of(), student, 422);
    put(
        "/personality-attempts/" + a + "/answers",
        Map.of("answers", List.of(Map.of("question_id", question, "option_id", "999999999"))),
        student,
        null,
        422);
    put(
        "/personality-attempts/" + a + "/answers",
        Map.of("answers", List.of(Map.of("question_id", question, "option_id", option))),
        student,
        null,
        204);
    post("/personality-attempts/" + a + "/result", Map.of(), student, 201);
    get("/personality-attempts/" + a + "/result", student, 200);
    get("/personality-attempts/" + a + "/recommendations", student, 200);
    put("/personality-attempts/" + a + "/saved-result", Map.of(), student, null, 204);
    get("/me/personality-results", student, 200);
    mvc.perform(
            MockMvcRequestBuilders.get("/api/v1/personality-attempts/" + a + "/report")
                .session(student))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"));
    put(
        "/personality-attempts/" + a + "/answers",
        Map.of("answers", List.of()),
        student,
        null,
        409);
  }

  @Test
  void operationsAndAccountEpoch() throws Exception {
    String source =
        id(
            "INSERT INTO crawl_sources(base_url,enabled)"
                + " VALUES('https://tuyensinh.utc.edu.vn/test',true) RETURNING id");
    get("/ops/crawl-sources", staff, 200);
    String job =
        post("/ops/crawl-jobs", Map.of("source_id", source), staff, 202).get("id").asText();
    post("/ops/crawl-jobs", Map.of("source_id", source), staff, 409);
    get("/ops/crawl-jobs", staff, 200);
    get("/ops/crawl-jobs/" + job, staff, 200);
    UUID target = ((Access.Account) student.getAttribute("account")).id();
    get("/admin/accounts", staff, 200);
    get("/admin/accounts/" + target, staff, 200);
    get("/admin/accounts/" + target + "/role-assignments", staff, 200);
    var grant =
        post(
            "/admin/accounts/" + target + "/role-assignments",
            Map.of("role_code", "STAFF_EDITOR", "campus_code", "TEST_HN"),
            staff,
            201);
    String assignment = grant.get("id").asText();
    get("/admin/role-assignments/" + assignment, staff, 200);
    get("/me", student, 401);
    call(
        MockMvcRequestBuilders.delete("/api/v1/admin/role-assignments/" + assignment)
            .session(staff),
        204);
    call(
        body(
            MockMvcRequestBuilders.patch("/api/v1/admin/accounts/" + target).session(staff),
            Map.of("status", "LOCKED")),
        200);
    UUID actor = ((Access.Account) staff.getAttribute("account")).id();
    call(
        body(
            MockMvcRequestBuilders.patch("/api/v1/admin/accounts/" + actor).session(staff),
            Map.of("status", "LOCKED")),
        409);
  }

  @Test
  void pdfUploadAndDocumentHistory() throws Exception {
    byte[] bytes;
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      pdf.addPage(new PDPage());
      pdf.save(out);
      bytes = out.toByteArray();
    }
    var file = new MockMultipartFile("file", "source.pdf", "application/pdf", bytes);
    var response =
        call(
            multipart("/api/v1/editorial/document-revisions")
                .file(file)
                .param("title", "Tài liệu mới")
                .param("official_url", "https://tuyensinh.utc.edu.vn/test-upload")
                .param("campus_code", "TEST_HN")
                .param("year", "2026")
                .session(staff),
            202);
    assertThat(response.get("status").asText()).isEqualTo("INDEXING");
    assertThat(response.has("storage_key")).isFalse();
    get("/editorial/documents", staff, 200);
    get("/editorial/document-revisions/" + response.get("id").asText(), staff, 200);
    get("/editorial/documents/" + response.get("document_id").asText() + "/revisions", staff, 200);
    get("/editorial/documents/" + document + "/publication", staff, 200);
    post(
        "/editorial/programs",
        Map.of("field_id", field, "code", "TEST_NEW", "name", "Ngành mới"),
        staff,
        201);
    get("/editorial/programs/" + program, staff, 200);
  }
}
