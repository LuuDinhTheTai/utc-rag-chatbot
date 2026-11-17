package vn.edu.utc.rag.security;

import jakarta.servlet.http.*;
import java.io.Serializable;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.exception.ApiException;

@Component
@Transactional()
public class Access {

  public record Account(UUID id, int epoch) implements Serializable {}

  public record Guest(String hash, Instant expires) implements Serializable {}

  public Access(
      vn.edu.utc.rag.persistence.repository.UsersRepository users,
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversations,
      vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository attempts,
      vn.edu.utc.rag.mapper.common.EntityMapper views) {
    this.users = users;
    this.conversations = conversations;
    this.attempts = attempts;
    this.views = views;
  }

  public UUID user(HttpServletRequest req) {
    var s = req.getSession(false);
    if (s == null || !(s.getAttribute("account") instanceof Account a)) return null;
    var account = users.findById(a.id());
    if (account.isEmpty()
        || !"ACTIVE".equals(account.get().getStatus())
        || account.get().getAuthEpoch() != a.epoch()) {
      s.invalidate();
      throw new ApiException(401, "UNAUTHENTICATED", "Session is no longer valid.");
    }
    return a.id();
  }

  public UUID requireUser(HttpServletRequest req) {
    UUID id = user(req);
    if (id == null) throw new ApiException(401, "UNAUTHENTICATED", "Sign in required.");
    return id;
  }

  public Guest guest(HttpServletRequest req, boolean create) {
    var s = req.getSession(create);
    if (s == null) return null;
    if (s.getAttribute("guest") instanceof Guest g && g.expires().isAfter(Instant.now())) return g;
    if (!create) return null;
    var g =
        new Guest(
            hash(UUID.randomUUID() + "/" + UUID.randomUUID()),
            Instant.now().plus(Duration.ofHours(24)));
    s.setAttribute("guest", g);
    return g;
  }

  public String caller(HttpServletRequest req) {
    UUID u = user(req);
    if (u != null) return "user:" + u;
    Guest g = guest(req, false);
    return g == null ? "public" : "guest:" + g.hash();
  }

  public Map<String, Object> own(String table, UUID id, HttpServletRequest req) {
    // Table names are supplied exclusively by application code.
    if (!Set.of("conversations", "personality_attempts").contains(table))
      throw new IllegalArgumentException();
    UUID u = user(req);
    Guest g = guest(req, false);
    if (u == null && g == null)
      throw new ApiException(401, "UNAUTHENTICATED", "Initialize a valid session first.");
    var row = findRecordsForOwn(Map.of("id", id), table);
    boolean owned = u != null && u.toString().equals(row.get("owner_user_id"));
    boolean guestOwned = g != null && g.hash().equals(row.get("guest_token_hash"));
    if (!owned && !guestOwned) throw ApiException.missing();
    if (row.get("expires_at") != null
        && !Instant.parse(row.get("expires_at").toString()).isAfter(Instant.now()))
      throw new ApiException(401, "UNAUTHENTICATED", "Guest resource has expired.");
    return row;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private final vn.edu.utc.rag.persistence.repository.UsersRepository users;

  private Map<String, Object> findRecordsForOwn(Map<String, ?> parameters, String table) {
    UUID id = (UUID) parameters.get("id");
    Object entity =
        switch (table) {
          case "conversations" ->
              conversations
                  .findById(id)
                  .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
          case "personality_attempts" ->
              attempts.findById(id).orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
          default -> throw new IllegalArgumentException("Unsupported ownership resource");
        };
    return views.of(entity);
  }

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversations;

  private final vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository attempts;

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;
}
