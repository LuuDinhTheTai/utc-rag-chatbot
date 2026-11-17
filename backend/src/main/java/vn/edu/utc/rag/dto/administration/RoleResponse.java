package vn.edu.utc.rag.dto.administration;

import java.time.Instant;
import java.util.UUID;

public record RoleResponse(
    Long id, UUID userId, String roleCode, String campusCode, Instant assignedAt) {}
