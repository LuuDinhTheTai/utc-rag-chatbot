package vn.edu.utc.rag.controller.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.service.chat.ChatService;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

  @PostMapping("/conversations")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var row = service.create(b, req);
    return ResponseEntity.created(URI.create("/api/v1/conversations/" + row.get("id"))).body(row);
  }

  @GetMapping("/me/conversations")
  Object history(HttpServletRequest req) {
    return service.history(req);
  }

  @GetMapping("/conversations/{id}")
  Object conversation(@PathVariable String id, HttpServletRequest req) {
    return service.conversation(id, req);
  }

  @DeleteMapping("/conversations/{id}")
  ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest req) {
    service.delete(id, req);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/conversations/{id}/messages")
  Object messages(@PathVariable String id, HttpServletRequest req) {
    return service.messages(id, req);
  }

  @GetMapping("/conversations/{id}/messages/{message}")
  Object message(@PathVariable String id, @PathVariable String message, HttpServletRequest req) {
    return service.message(id, message, req);
  }

  @PostMapping("/conversations/{id}/messages")
  ResponseEntity<?> send(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    var result = service.send(id, b, req);
    return ResponseEntity.status(result.replayed() ? 200 : 201).body(result.turn());
  }

  @PutMapping("/conversations/{id}/messages/{message}/feedback")
  ResponseEntity<?> feedback(
      @PathVariable String id,
      @PathVariable String message,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    service.feedback(id, message, b, req);
    return ResponseEntity.noContent().build();
  }

  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }
}
