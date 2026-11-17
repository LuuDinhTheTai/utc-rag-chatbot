package vn.edu.utc.rag.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

  @PostMapping("/conversations")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return service.create(b, req);
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
    return service.delete(id, req);
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
    return service.send(id, b, req);
  }

  @PutMapping("/conversations/{id}/messages/{message}/feedback")
  ResponseEntity<?> feedback(
      @PathVariable String id,
      @PathVariable String message,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    return service.feedback(id, message, b, req);
  }

  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }
}
