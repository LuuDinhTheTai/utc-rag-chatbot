package vn.edu.utc.rag.dto.ingestion;

import java.util.Map;

public record CrawlJobResult(Map<String, Object> job, boolean alreadyActive) {}
