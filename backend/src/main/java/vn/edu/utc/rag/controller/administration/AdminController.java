package vn.edu.utc.rag.controller.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.service.administration.AdminService;
import vn.edu.utc.rag.validation.Input;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private final AdminService service;

  public AdminController(AdminService service) {
    this.service = service;
  }

  @GetMapping("/accounts")
  Object accounts(HttpServletRequest r) {
    return service.accounts(r);
  }

  @GetMapping("/accounts/{id}")
  Object account(@PathVariable String id, HttpServletRequest r) {
    return service.account(Input.uuid(id), r);
  }

  @PatchMapping("/accounts/{id}")
  Object status(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return service.status(Input.uuid(id), b, r);
  }

  @GetMapping("/accounts/{id}/role-assignments")
  Object roles(@PathVariable String id, HttpServletRequest r) {
    return service.roles(Input.uuid(id), r);
  }

  @PostMapping("/accounts/{id}/role-assignments")
  ResponseEntity<?> grant(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    var result = service.grant(Input.uuid(id), b, r);
    return ResponseEntity.created(URI.create("/api/v1/admin/role-assignments/" + result.id()))
        .body(result);
  }

  @GetMapping("/role-assignments/{id}")
  Object role(@PathVariable String id, HttpServletRequest r) {
    return service.role(Input.id(id), r);
  }

  @DeleteMapping("/role-assignments/{id}")
  ResponseEntity<?> revoke(@PathVariable String id, HttpServletRequest r) {
    service.revoke(Input.id(id), r);
    return ResponseEntity.noContent().build();
  }
}
