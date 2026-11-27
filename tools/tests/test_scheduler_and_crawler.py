"""Tests for CrawlScheduler, CrawlEngine discovery failure handling, and LeaseKeeper."""

import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, Mock

import pytest

from src.config import Config, CrawlerConfig, DatabaseConfig, OllamaConfig, WorkerConfig
from src.crawler import CrawlEngine
from src.database import Database
from src.exceptions import ChunkCountMismatchError, DiscoveryError, HTTPError, LeaseExpiredError
from src.job_context import JobContext, LeaseKeeper
from src.job_manager import JobManager
from src.main import Worker
from src.revision_processor import RevisionProcessor
from src.scheduler import CrawlScheduler
from src.source_registry import CrawlSource


def _setup_tx(mock_db, mock_cursor):
    mock_conn = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_conn.cursor.return_value.__exit__.return_value = False
    mock_db.transaction = MagicMock()
    mock_db.transaction.return_value.__enter__.return_value = mock_conn
    mock_db.transaction.return_value.__exit__.return_value = False


class TestCrawlScheduler:
    def test_queries_without_name_column(self):
        db = Mock(spec=Database)
        db.execute.return_value = [{"id": 1, "base_url": "https://utc.edu.vn", "crawl_interval_hours": 6}]
        sched = CrawlScheduler(db, register_signals=False)

        sources = sched._get_enabled_sources()
        assert len(sources) == 1
        sql = db.execute.call_args[0][0]
        assert "base_url" in sql
        assert "name" not in sql.lower()

    def test_should_schedule_cases(self):
        db = Mock(spec=Database)
        sched = CrawlScheduler(db, default_interval_hours=6, register_signals=False)
        now = datetime.now(timezone.utc)
        src = {"id": 1, "base_url": "https://utc.edu.vn", "crawl_interval_hours": 4}

        # 1. Active PENDING/RUNNING job -> skip
        db.execute_one.return_value = {"id": 10, "status": "RUNNING"}
        assert sched._should_schedule_job(src) is False

        # 2. No previous finished job -> schedule
        db.execute_one.side_effect = [None, None]
        assert sched._should_schedule_job(src) is True

        # 3. Last COMPLETED 2h ago (interval 4h) -> skip
        db.execute_one.side_effect = [
            None,
            {"id": 9, "status": "COMPLETED", "completed_at": now - timedelta(hours=2), "created_at": now - timedelta(hours=5), "db_now": now},
        ]
        assert sched._should_schedule_job(src) is False

        # 4. Last COMPLETED 4.5h ago (interval 4h) -> schedule
        db.execute_one.side_effect = [
            None,
            {"id": 9, "status": "COMPLETED", "completed_at": now - timedelta(hours=4, minutes=30), "created_at": now - timedelta(hours=5), "db_now": now},
        ]
        assert sched._should_schedule_job(src) is True

        # 5. Last FAILED 5h ago -> schedule
        db.execute_one.side_effect = [
            None,
            {"id": 8, "status": "FAILED", "completed_at": now - timedelta(hours=5), "created_at": now - timedelta(hours=6), "db_now": now},
        ]
        assert sched._should_schedule_job(src) is True

    def test_concurrent_scheduler_on_conflict_do_nothing(self):
        db = Mock(spec=Database)
        cur = MagicMock()
        _setup_tx(db, cur)
        cur.fetchone.return_value = None  # Another scheduler won the race
        sched = CrawlScheduler(db, register_signals=False)

        assert sched._create_job({"id": 1, "base_url": "https://utc.edu.vn"}) is None
        assert "ON CONFLICT" in cur.execute.call_args[0][0]


class TestCrawlerDiscoveryAndLeaseKeeper:
    def test_homepage_discovery_failure_raises_discovery_error(self):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False)
        http = MagicMock()
        http.fetch.side_effect = HTTPError(503, "Service Unavailable", "https://utc.edu.vn/")
        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=Mock(spec=RevisionProcessor),
            http_client=http,
        )
        engine.security_validator = MagicMock()
        src = CrawlSource(id=1, base_url="https://utc.edu.vn", sitemap_url=None, enabled=True, rate_limit_seconds=0.0)

        with pytest.raises(DiscoveryError, match="Failed to fetch homepage"):
            engine.crawl_source(src, job_id=1, job_claim_token=uuid.uuid4())

    def test_lease_keeper_marks_context_lost_on_ownership_error(self):
        token = uuid.uuid4()
        ctx = JobContext(job_id=42, claim_token=token)
        calls = []

        def fake_hb(jid, tok):
            calls.append((jid, tok))
            if len(calls) >= 2:
                raise LeaseExpiredError("lease expired")

        with LeaseKeeper(fake_hb, ctx, interval_seconds=0.05, lease_duration_seconds=0.2):
            deadline = time.monotonic() + 1.0
            while ctx.lost_error is None and time.monotonic() < deadline:
                time.sleep(0.02)

        assert isinstance(ctx.lost_error, LeaseExpiredError)
        with pytest.raises(LeaseExpiredError):
            ctx.checkpoint()

    def test_chunk_count_mismatch_propagates_to_worker_and_fails_job(self):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False, max_discovery_depth=0)
        http = MagicMock()
        http.fetch.return_value = (b"<html><body><p>Valid text</p></body></html>", "text/html", None)
        rp = Mock(spec=RevisionProcessor)
        rp.process_document.side_effect = ChunkCountMismatchError("chunk count mismatch")

        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=rp,
            http_client=http,
        )
        engine.security_validator = MagicMock()
        src = CrawlSource(id=1, base_url="https://utc.edu.vn", sitemap_url=None, enabled=True, rate_limit_seconds=0.0)

        with pytest.raises(ChunkCountMismatchError):
            engine.crawl_source(src, job_id=7, job_claim_token=uuid.uuid4())

        # Verify Worker marks job failed (retryable=True) instead of COMPLETED
        app_cfg = Config(
            database=DatabaseConfig("localhost", 5432, "db", "utc_worker", "pw"),
            ollama=OllamaConfig("http://localhost:11434", "qwen3:0.6b", "embeddinggemma:300m-qat-q4_0", 768),
            crawler=cfg,
            worker=WorkerConfig(lease_duration_seconds=120, heartbeat_interval_seconds=60),
        )
        worker = Worker(app_cfg, register_signals=False)
        worker.source_registry = MagicMock()
        worker.source_registry.get_source_by_id.return_value = src
        worker.source_registry.validate_source_config.return_value = []
        worker.crawler = engine
        worker.job_manager = MagicMock()

        tok = uuid.uuid4()
        worker._process_job({"id": 7, "source_id": 1, "claim_token": tok, "attempt_no": 1})
        worker.job_manager.complete_job.assert_not_called()
        worker.job_manager.fail_job.assert_called_once()
        _, kwargs = worker.job_manager.fail_job.call_args
        assert kwargs["retryable"] is True

    @pytest.mark.parametrize(
        "partial_meta",
        [
            {"partial_extraction": True, "failed_pages": [2], "truncated": False, "num_pages": 3, "pages_attempted": 3},
            {"partial_extraction": True, "failed_pages": [], "truncated": True, "num_pages": 600, "pages_attempted": 500},
        ],
    )
    def test_pdf_partial_extraction_is_rejected_and_not_indexed(self, partial_meta):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False, max_discovery_depth=0)
        http = MagicMock()
        http.fetch.return_value = (b"%PDF-1.4 fake", "application/pdf", None)
        rp = Mock(spec=RevisionProcessor)

        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=rp,
            http_client=http,
        )
        engine.security_validator = MagicMock()
        engine.pdf_extractor = MagicMock()
        engine.pdf_extractor.extract.return_value = {
            "text": "[Page 1]\nPartial content",
            "title": "Partial PDF",
            "metadata": partial_meta,
        }
        src = CrawlSource(id=1, base_url="https://utc.edu.vn/doc.pdf", sitemap_url=None, enabled=True, rate_limit_seconds=0.0)

        report = engine.crawl_source(src, job_id=9, job_claim_token=uuid.uuid4())
        rp.process_document.assert_not_called()
        assert report.status == "failed"
        assert report.error_count == 1
        assert "Partial extraction rejected" in (report.results[0].error_message or "")

    def test_sitemap_child_failure_reported_in_crawl_report_warnings(self):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False)
        index_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <sitemap><loc>https://utc.edu.vn/s-ok.xml</loc></sitemap>
    <sitemap><loc>https://utc.edu.vn/s-bad.xml</loc></sitemap>
</sitemapindex>"""
        ok_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/page-1</loc></url>
</urlset>"""
        http = MagicMock()
        http.fetch.side_effect = [
            (index_xml, "application/xml", None),
            (ok_xml, "application/xml", None),
            HTTPError(500, "Server Error", "https://utc.edu.vn/s-bad.xml"),
            (b"<html><body><p>Page 1 content</p></body></html>", "text/html", None),
        ]
        rp = Mock(spec=RevisionProcessor)
        rp.process_document.return_value = {
            "document_id": 1,
            "revision_id": 2,
            "chunks_created": 1,
            "content_changed": True,
            "document_created": True,
            "skipped_reason": None,
        }

        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=rp,
            http_client=http,
        )
        engine.security_validator = MagicMock()
        src = CrawlSource(
            id=1,
            base_url="https://utc.edu.vn",
            sitemap_url="https://utc.edu.vn/sitemap.xml",
            enabled=True,
            rate_limit_seconds=0.0,
        )

        report = engine.crawl_source(src, job_id=10, job_claim_token=uuid.uuid4())
        assert report.status == "completed"
        assert report.success_count == 1
        assert any("s-bad.xml" in w for w in report.warnings)
        assert report.error_summary and "s-bad.xml" in report.error_summary

    def test_404_archives_existing_revisions_as_deleted(self):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False)
        xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/old-page</loc></url>
</urlset>"""
        http = MagicMock()
        http.fetch.side_effect = [
            (xml, "application/xml", None),
            HTTPError(404, "Not Found", "https://utc.edu.vn/old-page"),
        ]
        rp = Mock(spec=RevisionProcessor)
        rp.archive_document_revisions.return_value = 1
        rp.archive_missing_source_documents.return_value = 0

        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=rp,
            http_client=http,
        )
        engine.security_validator = MagicMock()
        src = CrawlSource(id=1, base_url="https://utc.edu.vn", sitemap_url="https://utc.edu.vn/sitemap.xml", enabled=True, rate_limit_seconds=0.0)

        report = engine.crawl_source(src, job_id=11, job_claim_token=uuid.uuid4())
        assert report.deleted_count == 1
        rp.archive_document_revisions.assert_called_once()
        rp.archive_missing_source_documents.assert_called_once()

    def test_campus_id_inference_for_utc_and_utc2(self):
        cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False)
        engine = CrawlEngine(
            config=cfg,
            database=Mock(spec=Database),
            job_manager=Mock(spec=JobManager),
            revision_processor=Mock(spec=RevisionProcessor),
            http_client=MagicMock(),
        )
        assert engine._infer_document_attributes("https://tuyensinh.utc.edu.vn/nganh-2026", {})["campus_id"] == 1
        assert engine._infer_document_attributes("https://utc2.edu.vn/tuyen-sinh-2026", {})["campus_id"] == 2
        assert engine._infer_document_attributes("https://tuyensinh.utc2.edu.vn/dao-tao", {})["campus_id"] == 2
        assert engine._infer_document_attributes("https://diemthi.tuyensinh247.com/dai-hoc-gtvt-co-so-phia-bac-GHA.html", {})["campus_id"] == 1
        assert engine._infer_document_attributes("https://diemthi.tuyensinh247.com/dai-hoc-gtvt-phan-hieu-GSA.html", {})["campus_id"] == 2
