package vn.edu.utc.rag.dto.administration;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    UUID id, String email, String status, Integer authEpoch, Instant createdAt) {}
