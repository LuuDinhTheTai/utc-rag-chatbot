"""Crawler Engine — orchestrates discovery, fetching, extraction, and revision ingestion.

Key guarantees:
- Returns a structured ``CrawlReport`` so the worker can distinguish success,
  partial success, empty site, and discovery failure (no fake COMPLETED jobs).
- Never swallows ``OwnershipLostError`` or ``ShutdownRequested``.
- Honors ``config.respect_robots_txt`` and robots.txt ``Crawl-delay``.
- Applies rate limiting in a single place (``HTTPClient.rate_limiter``) with no
  double-sleeping.
- Parses HTML/PDF/DOCX once per URL (text + title + metadata together).
- Rejects legacy ``.doc`` (``application/msword``) explicitly.
"""

import logging
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

from .config import CrawlerConfig
from .database import Database
from .exceptions import (
    ChunkCountMismatchError,
    ContentTooLargeError,
    DatabaseError,
    DiscoveryError,
    EmbeddingError,
    EmptyContentError,
    HTTPError,
    OwnershipLostError,
    ParsingError,
    RobotsDisallowedError,
    RobotsUnavailableError,
    SecurityError,
    ShutdownRequested,
    UnsupportedContentTypeError,
    ValidationError,
)
from .extractors.docx import DOCXExtractor
from .extractors.html import HTMLExtractor
from .extractors.pdf import PDFExtractor
from .http_client import HTTPClient
from .job_context import JobContext
from .job_manager import JobManager
from .revision_processor import RevisionProcessor
from .robots import RobotsChecker
from .security_validator import SecurityValidator
from .sitemap_parser import SitemapParser, SitemapURL
from .source_registry import CrawlSource, SourceRegistry
from .url_discovery import URLDiscovery
from .url_utils import get_hostname, normalize_url

logger = logging.getLogger(__name__)


@dataclass
class CrawlResult:
    """Result of crawling a single URL."""

    url: str
    success: bool
    status: str  # 'new', 'changed', 'unchanged', 'skipped', 'error'
    error_message: Optional[str] = None
    document_id: Optional[int] = None
    revision_id: Optional[int] = None
    content_type: Optional[str] = None
    content_size: Optional[int] = None


@dataclass
class CrawlReport:
    """Structured outcome of crawling a source."""

    source_id: int
    status: str  # 'completed', 'partial', 'empty', 'failed'
    discovery_method: str  # 'sitemap', 'homepage', 'none'
    urls_discovered: int
    results: List[CrawlResult] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    @property
    def success_count(self) -> int:
        return sum(1 for r in self.results if r.success)

    @property
    def error_count(self) -> int:
        return sum(1 for r in self.results if not r.success)

    @property
    def new_count(self) -> int:
        return sum(1 for r in self.results if r.status == "new")

    @property
    def changed_count(self) -> int:
        return sum(1 for r in self.results if r.status == "changed")

    @property
    def unchanged_count(self) -> int:
        return sum(1 for r in self.results if r.status == "unchanged")

    @property
    def deleted_count(self) -> int:
        return sum(1 for r in self.results if r.status == "deleted")

    @property
    def error_summary(self) -> Optional[str]:
        parts: List[str] = []
        if self.warnings:
            parts.extend(self.warnings[:3])
        if self.error_count > 0:
            sample = [
                f"{r.url}: {r.error_message}"
                for r in self.results
                if not r.success and r.error_message
            ][:3]
            parts.append(
                f"{self.error_count}/{len(self.results)} URLs failed"
                + (f" ({'; '.join(sample)})" if sample else "")
            )
        return "; ".join(parts) if parts else None


class CrawlEngine:
    """Main crawler engine orchestrating discovery and document ingestion."""

    def __init__(
        self,
        config: CrawlerConfig,
        database: Database,
        job_manager: JobManager,
        revision_processor: RevisionProcessor,
        *,
        http_client: Optional[HTTPClient] = None,
        robots_checker: Optional[RobotsChecker] = None,
    ):
        self.config = config
        self.database = database
        self.job_manager = job_manager
        self.revision_processor = revision_processor

        self.security_validator = SecurityValidator(
            allowed_schemes=["http", "https"],
            allow_private_ips=False,
            resolve_dns=True,
        )
        self.http_client = http_client or HTTPClient(config, security_validator=self.security_validator)
        self.source_registry = SourceRegistry(
            database, default_rate_limit_seconds=config.default_rate_limit_seconds
        )
        self.robots_checker = robots_checker or RobotsChecker(
            config.user_agent,
            http_client=self.http_client,
            respect_robots_txt=config.respect_robots_txt,
            cache_ttl_seconds=config.robots_cache_ttl_seconds,
            max_crawl_delay_seconds=config.max_crawl_delay_seconds,
        )

        # OCR is disabled by default (out of scope for this release)
        self.ocr_extractor = None
        if config.enable_ocr:
            try:
                from .extractors.image_ocr import create_ocr_extractor
                self.ocr_extractor = create_ocr_extractor(
                    enable_ocr=True,
                    languages=list(config.ocr_languages),
                    use_gpu=config.ocr_use_gpu,
                )
            except Exception as e:
                logger.warning("Failed to initialize optional OCR: %s", e)
                self.ocr_extractor = None

        self.html_extractor = HTMLExtractor(
            ocr_extractor=self.ocr_extractor,
            http_client=self.http_client if self.ocr_extractor else None,
        )
        self.pdf_extractor = PDFExtractor(max_pages=config.max_pdf_pages)
        self.docx_extractor = DOCXExtractor(
            max_uncompressed_bytes=config.max_docx_uncompressed_mb * 1024 * 1024
        )

    def crawl_source(
        self,
        source: CrawlSource,
        job_id: int,
        job_claim_token: uuid.UUID,
        *,
        job_context: Optional[JobContext] = None,
    ) -> CrawlReport:
        """Crawl a single source and process all discovered URLs.

        Raises:
            OwnershipLostError: If lease expires or token changes.
            ShutdownRequested: If worker shutdown is requested.
            DiscoveryError: If site/sitemap/homepage discovery fails.
            ValidationError: If source configuration (e.g. regex) is invalid.
        """
        ctx = job_context or JobContext(job_id=job_id, claim_token=job_claim_token)
        ctx.checkpoint()

        logger.info("Starting crawl for source %s: %s", source.id, source.base_url)

        # Validate source regexes up front
        self.source_registry.ensure_valid_patterns(source)

        discovered_urls, discovery_method, warnings = self._discover_urls(source, ctx)
        if not discovered_urls:
            logger.info(
                "Source %s (%s) reachable via %s but yielded 0 matching URLs",
                source.id, source.base_url, discovery_method,
            )
            return CrawlReport(
                source_id=source.id,
                status="empty",
                discovery_method=discovery_method,
                urls_discovered=0,
                results=[],
                warnings=warnings,
            )

        results: List[CrawlResult] = []
        processed: Set[str] = set()

        for url in discovered_urls:
            ctx.checkpoint()

            try:
                canonical = normalize_url(url)
            except ValueError:
                continue
            if canonical in processed:
                continue
            processed.add(canonical)

            result = self._process_url(canonical, source, ctx)
            results.append(result)

        # If discovery came from a complete sitemap (no child failures, not truncated at max_sitemap_urls),
        # archive revisions of URLs from this source that disappeared from the sitemap.
        if (
            discovery_method == "sitemap"
            and processed
            and not any("Child sitemap failed" in w for w in warnings)
            and len(discovered_urls) < self.config.max_sitemap_urls
        ):
            archived = self.revision_processor.archive_missing_source_documents(
                ctx.job_id, ctx.claim_token, source.id, list(processed)
            )
            if archived > 0:
                warnings.append(f"Archived {archived} revision(s) removed from sitemap")

        if not results:
            status = "empty"
        elif all(r.success for r in results):
            status = "completed"
        elif any(r.success for r in results):
            status = "partial"
        else:
            status = "failed"

        report = CrawlReport(
            source_id=source.id,
            status=status,
            discovery_method=discovery_method,
            urls_discovered=len(discovered_urls),
            results=results,
            warnings=warnings,
        )
        logger.info(
            "Finished crawl for source %s (status=%s, method=%s): %d/%d succeeded "
            "(%d new, %d changed, %d unchanged, %d errors)",
            source.id, report.status, report.discovery_method,
            report.success_count, len(results),
            report.new_count, report.changed_count, report.unchanged_count, report.error_count,
        )
        return report

    def _discover_urls(
        self, source: CrawlSource, ctx: JobContext
    ) -> Tuple[List[str], str, List[str]]:
        """Discover URLs from sitemap (if configured) or homepage.

        Raises ``DiscoveryError`` when discovery fails due to network/robots/security
        errors rather than returning an empty list.
        """
        warnings: List[str] = []
        sitemap_error: Optional[str] = None

        if source.sitemap_url:
            logger.info("Discovering URLs from sitemap: %s", source.sitemap_url)
            try:
                sitemap_urls, failed_children = self._discover_from_sitemap(source, ctx)
                for err in failed_children:
                    warnings.append(f"Child sitemap failed: {err}")
                if sitemap_urls:
                    urls = self._prioritize_urls([u.loc for u in sitemap_urls])
                    return urls, "sitemap", warnings
                warnings.append(f"Sitemap {source.sitemap_url} contained 0 matching URLs; tried homepage")
            except (OwnershipLostError, ShutdownRequested, ValidationError):
                raise
            except HTTPError as e:
                if e.status_code in (404, 410):
                    logger.info(
                        "Sitemap %s returned HTTP %d; falling back to homepage discovery",
                        source.sitemap_url, e.status_code,
                    )
                    warnings.append(f"Sitemap not found (HTTP {e.status_code}); used homepage discovery")
                else:
                    sitemap_error = f"Sitemap fetch failed ({e})"
                    logger.warning("%s; falling back to homepage discovery", sitemap_error)
                    warnings.append(sitemap_error)
            except (SecurityError, RobotsUnavailableError) as e:
                sitemap_error = f"Sitemap blocked/failed ({e})"
                logger.warning("%s; falling back to homepage discovery", sitemap_error)
                warnings.append(sitemap_error)

        # Homepage discovery
        try:
            homepage_urls = self._discover_from_homepage(source, ctx)
            return homepage_urls, "homepage", warnings
        except (OwnershipLostError, ShutdownRequested, ValidationError):
            raise
        except DiscoveryError as e:
            if sitemap_error:
                raise DiscoveryError(f"{sitemap_error}; homepage discovery also failed: {e}") from e
            raise

    def _discover_from_sitemap(
        self, source: CrawlSource, ctx: JobContext
    ) -> Tuple[List[SitemapURL], List[str]]:
        if not source.sitemap_url:
            return [], []

        if self.config.respect_robots_txt:
            if not self.robots_checker.is_allowed(source.sitemap_url, job_context=ctx):
                raise RobotsDisallowedError(f"Sitemap disallowed by robots.txt: {source.sitemap_url}")

        rate_limit = self._get_effective_rate_limit(source.sitemap_url, source, ctx)
        parser = SitemapParser(
            http_client=self.http_client,
            allowed_domains=[get_hostname(source.base_url)],
            allowed_path_patterns=source.allowed_path_patterns,
            excluded_path_patterns=source.excluded_path_patterns,
            max_urls=self.config.max_sitemap_urls,
            max_sitemaps=self.config.max_sitemap_files,
        )
        urls = parser.parse_sitemap(
            source.sitemap_url,
            rate_limit_override=rate_limit,
            job_context=ctx,
        )
        return urls, list(parser.failed_child_sitemaps)

    def _discover_from_homepage(
        self,
        source: CrawlSource,
        ctx: JobContext,
    ) -> List[str]:
        """BFS link discovery starting from ``source.base_url``.

        Raises ``DiscoveryError`` if the entry URL (homepage) cannot be fetched
        or is disallowed by robots.txt.
        """
        max_urls = self.config.max_discovery_urls
        max_depth = self.config.max_discovery_depth
        entry_url = normalize_url(source.base_url)

        logger.info("Starting homepage discovery for %s (max_urls=%d, max_depth=%d)", entry_url, max_urls, max_depth)

        # Entry URL must pass security & robots checks
        try:
            self.security_validator.validate_url(entry_url, context="(homepage)")
        except SecurityError as e:
            raise DiscoveryError(f"Homepage failed security validation: {e}") from e

        try:
            if not self._check_robots_allowed(entry_url, ctx):
                raise DiscoveryError(f"Homepage disallowed by robots.txt: {entry_url}")
        except RobotsUnavailableError as e:
            raise DiscoveryError(f"robots.txt unavailable for {entry_url}: {e}") from e

        url_discoverer = URLDiscovery(
            base_url=entry_url,
            allowed_domains=[get_hostname(entry_url)],
            allowed_path_patterns=source.allowed_path_patterns,
            excluded_path_patterns=source.excluded_path_patterns,
            max_depth=max_depth,
            same_domain_only=True,
        )

        discovered_urls: List[str] = []
        discovered_set: Set[str] = set()
        visited: Set[str] = set()
        to_visit: List[str] = [entry_url]
        depth = 0
        entry_fetched = False

        while to_visit and len(discovered_urls) < max_urls and depth <= max_depth:
            ctx.checkpoint()
            current_level = to_visit
            to_visit = []

            for url in current_level:
                ctx.checkpoint()
                if url in visited:
                    continue
                if len(discovered_urls) >= max_urls:
                    break
                visited.add(url)

                if url != entry_url:
                    try:
                        if not self._check_robots_allowed(url, ctx):
                            continue
                        self.security_validator.validate_url(url)
                    except (OwnershipLostError, ShutdownRequested):
                        raise
                    except (SecurityError, RobotsUnavailableError) as e:
                        logger.debug("Skipping discovered URL %s: %s", url, e)
                        continue

                # Fetch to discover links (and verify entry URL is reachable)
                if depth < max_depth or url == entry_url:
                    try:
                        content, content_type, _ = self._fetch_content(url, source, ctx)
                        if url == entry_url:
                            entry_fetched = True
                    except (OwnershipLostError, ShutdownRequested):
                        raise
                    except (HTTPError, SecurityError, ContentTooLargeError, UnsupportedContentTypeError, RobotsUnavailableError) as e:
                        if url == entry_url:
                            raise DiscoveryError(f"Failed to fetch homepage {entry_url}: {e}") from e
                        logger.debug("Failed to fetch %s during discovery: %s", url, e)
                        continue

                    if url not in discovered_set:
                        discovered_set.add(url)
                        discovered_urls.append(url)

                    if depth < max_depth and content_type in ("text/html", "application/xhtml+xml"):
                        html_text = content.decode("utf-8", errors="replace")
                        new_links = url_discoverer.discover_links(
                            html_text, current_url=url, current_depth=depth
                        )
                        for link in self._prioritize_urls(new_links):
                            if link not in visited and link not in discovered_set:
                                to_visit.append(link)
                else:
                    if url not in discovered_set:
                        discovered_set.add(url)
                        discovered_urls.append(url)

            depth += 1

        if not entry_fetched and not discovered_urls:
            raise DiscoveryError(f"Could not fetch homepage {entry_url}")

        return discovered_urls[:max_urls]

    def _prioritize_urls(self, urls: List[str]) -> List[str]:
        high_priority = (
            "nganh-tuyen-sinh", "thong-tin-tuyen-sinh", "chuong-trinh",
            "dao-tao", "chi-tieu", "phuong-thuc", "to-hop-mon", "diem-chuan",
        )
        medium_priority = ("thong-tin-nganh", "hoi-dap", "quy-che")
        low_priority = ("tin-tuc", "hoat-dong", "lien-he", "gioi-thieu", "thu-vien-anh")

        def score(u: str) -> int:
            low = u.lower()
            if any(k in low for k in high_priority):
                return 3
            if any(k in low for k in medium_priority):
                return 2
            if any(k in low for k in low_priority):
                return 0
            return 1

        return sorted(urls, key=lambda u: (-score(u), u))

    def _process_url(
        self,
        canonical_url: str,
        source: CrawlSource,
        ctx: JobContext,
    ) -> CrawlResult:
        """Fetch, extract (single pass), and ingest a single URL."""
        logger.info("Processing URL: %s", canonical_url)
        ctx.checkpoint()

        try:
            if not self._check_robots_allowed(canonical_url, ctx):
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status="error",
                    error_message="Disallowed by robots.txt",
                )

            content, content_type, _last_modified = self._fetch_content(canonical_url, source, ctx)
            if not content:
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status="error",
                    error_message="Empty HTTP response body",
                    content_type=content_type,
                )

            extracted = self._extract_content(content, content_type, canonical_url)
            metadata = extracted.get("metadata") or {}
            if metadata.get("partial_extraction"):
                reasons = []
                failed_pages = metadata.get("failed_pages") or []
                if failed_pages:
                    reasons.append(f"failed pages {failed_pages}")
                if metadata.get("truncated"):
                    reasons.append(
                        f"truncated at {metadata.get('pages_attempted')}/{metadata.get('num_pages')} pages"
                    )
                detail = ", ".join(reasons) if reasons else "incomplete extraction"
                raise ParsingError(
                    f"Partial extraction rejected for {canonical_url} ({detail})"
                )

            extracted_text = (extracted.get("text") or "").strip()
            if not extracted_text:
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status="error",
                    error_message="No extractable text",
                    content_type=content_type,
                    content_size=len(content),
                )

            title = extracted.get("title") or "Untitled"
            attrs = self._infer_document_attributes(canonical_url, {"title": title})

            revision_result = self.revision_processor.process_document(
                job_id=ctx.job_id,
                job_claim_token=ctx.claim_token,
                canonical_url=canonical_url,
                title=title,
                extracted_text=extracted_text,
                content_type=content_type,
                source_id=source.id,
                campus_id=attrs.get("campus_id"),
                admission_year=attrs.get("admission_year"),
                job_context=ctx,
            )

            if not revision_result["content_changed"]:
                status = "unchanged"
            elif revision_result.get("document_created"):
                status = "new"
            else:
                status = "changed"

            return CrawlResult(
                url=canonical_url,
                success=True,
                status=status,
                document_id=revision_result.get("document_id"),
                revision_id=revision_result.get("revision_id"),
                content_type=content_type,
                content_size=len(content),
            )

        except (
            OwnershipLostError,
            ShutdownRequested,
            DatabaseError,
            EmbeddingError,
            ChunkCountMismatchError,
        ):
            # Job-level / data-integrity / infrastructure errors must abort the job,
            # never be downgraded into per-URL errors.
            raise

        except HTTPError as e:
            if e.status_code in (404, 410):
                archived = self.revision_processor.archive_document_revisions(
                    ctx.job_id,
                    ctx.claim_token,
                    canonical_url,
                    reason=f"HTTP {e.status_code}",
                )
                if archived > 0:
                    logger.info(
                        "URL %s removed (HTTP %d); archived %d old revision(s)",
                        canonical_url, e.status_code, archived,
                    )
                    return CrawlResult(
                        url=canonical_url,
                        success=True,
                        status="deleted",
                        error_message=f"HTTP {e.status_code} (archived {archived} revision(s))",
                    )
            logger.warning("URL %s failed: %s", canonical_url, e)
            return CrawlResult(
                url=canonical_url,
                success=False,
                status="error",
                error_message=str(e),
            )

        except (
            RobotsDisallowedError,
            RobotsUnavailableError,
            SecurityError,
            ContentTooLargeError,
            ParsingError,
            EmptyContentError,
            UnsupportedContentTypeError,
        ) as e:
            logger.warning("URL %s failed: %s", canonical_url, e)
            return CrawlResult(
                url=canonical_url,
                success=False,
                status="error",
                error_message=str(e),
            )

        except Exception as e:
            logger.error("Unexpected error processing %s: %s", canonical_url, e, exc_info=True)
            return CrawlResult(
                url=canonical_url,
                success=False,
                status="error",
                error_message=f"Unexpected error: {e}",
            )

    def _check_robots_allowed(self, url: str, ctx: Optional[JobContext] = None) -> bool:
        if not self.config.respect_robots_txt:
            return True
        return self.robots_checker.is_allowed(url, job_context=ctx)

    def _get_effective_rate_limit(
        self, url: str, source: CrawlSource, ctx: Optional[JobContext] = None
    ) -> float:
        """Compute effective rate limit = max(source/config rate limit, robots crawl-delay)."""
        base_limit = (
            source.rate_limit_seconds
            if source.rate_limit_seconds is not None
            else self.config.default_rate_limit_seconds
        )
        robots_delay: Optional[float] = None
        if self.config.respect_robots_txt:
            robots_delay = self.robots_checker.get_crawl_delay(url, job_context=ctx)
        if robots_delay is not None:
            return max(float(base_limit), float(robots_delay))
        return float(base_limit)

    def _fetch_content(
        self, url: str, source: CrawlSource, ctx: Optional[JobContext] = None
    ) -> Tuple[bytes, str, Optional[str]]:
        """Fetch URL using HTTPClient (single rate-limiting layer, no double sleep)."""
        rate_limit = self._get_effective_rate_limit(url, source, ctx)
        return self.http_client.fetch(
            url,
            respect_rate_limit=True,
            validate_security=True,
            rate_limit_override=rate_limit,
            job_context=ctx,
        )

    def _extract_content(self, content: bytes, content_type: str, url: str) -> Dict[str, Any]:
        """Single-pass extraction returning text, title, and metadata."""
        mime = (content_type or "").split(";", 1)[0].strip().lower()
        if mime in ("text/html", "application/xhtml+xml"):
            return self.html_extractor.extract(content, url)
        if mime == "application/pdf":
            return self.pdf_extractor.extract(content, url)
        if mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            return self.docx_extractor.extract(content, url)
        if mime == "application/msword":
            raise UnsupportedContentTypeError(
                f"Legacy .doc (application/msword) is not supported; only .docx is supported ({url})"
            )
        raise UnsupportedContentTypeError(f"Unsupported content type: {content_type} ({url})")

    def _infer_document_attributes(self, url: str, metadata: dict) -> dict:
        """Infer campus_id (1 = UTC Hà Nội, 2 = UTC2 TP.HCM) and admission_year."""
        attributes: Dict[str, Any] = {}
        host = get_hostname(url)
        url_lower = url.lower()

        # Check UTC2 (TP.HCM) first so 'utc2.edu.vn' does not match generic 'utc'
        if (
            host == "utc2.edu.vn"
            or host.endswith(".utc2.edu.vn")
            or any(k in url_lower for k in ("ho-chi-minh", "hcm", "co-so-2", "phan-hieu", "-gsa"))
        ):
            attributes["campus_id"] = 2
        elif (
            host == "utc.edu.vn"
            or host.endswith(".utc.edu.vn")
            or any(k in url_lower for k in ("ha-noi", "hanoi", "co-so-phia-bac", "-gha"))
        ):
            attributes["campus_id"] = 1

        year_pattern = r"20[2-3][0-9]"
        year_match = re.search(year_pattern, url)
        if year_match:
            attributes["admission_year"] = int(year_match.group())
        elif metadata.get("title"):
            year_match = re.search(year_pattern, str(metadata["title"]))
            if year_match:
                attributes["admission_year"] = int(year_match.group())
        return attributes

    def close(self) -> None:
        self.http_client.close()
