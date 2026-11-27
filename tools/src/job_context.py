"""Per-job execution context: time-based heartbeat + cooperative cancellation.

``LeaseKeeper`` runs a single background thread that extends the job lease every
``heartbeat_interval`` seconds, independent of how many URLs were processed, so
long operations (sitemap parsing, slow HTTP, PDF parsing, embedding, database
inserts) cannot silently outlive the lease.

The processing thread calls ``JobContext.checkpoint()`` between units of work:
it raises the recorded ``OwnershipLostError`` as soon as the heartbeat thread
learns that ownership is gone, or ``ShutdownRequested`` after a signal. Database
writes are additionally fenced in SQL, so even a delayed checkpoint can never
let a stale worker persist data.
"""

import logging
import threading
import time
import uuid
from typing import Callable, Optional

from .exceptions import LeaseExpiredError, OwnershipLostError, ShutdownRequested

logger = logging.getLogger(__name__)


class JobContext:
    """Identity of the running job plus cancellation state."""

    def __init__(
        self,
        job_id: int,
        claim_token: uuid.UUID,
        stop_event: Optional[threading.Event] = None,
    ):
        self.job_id = job_id
        self.claim_token = claim_token
        self.stop_event = stop_event or threading.Event()
        self._lost: Optional[OwnershipLostError] = None
        self._lock = threading.Lock()

    def mark_lost(self, error: OwnershipLostError) -> None:
        with self._lock:
            if self._lost is None:
                self._lost = error

    @property
    def lost_error(self) -> Optional[OwnershipLostError]:
        with self._lock:
            return self._lost

    def checkpoint(self) -> None:
        """Raise if ownership was lost or shutdown was requested."""
        lost = self.lost_error
        if lost is not None:
            raise lost
        if self.stop_event.is_set():
            raise ShutdownRequested(f"Shutdown requested while processing job {self.job_id}")

    def sleep(self, seconds: float) -> None:
        """Interruptible sleep that honours shutdown/ownership loss."""
        if seconds <= 0:
            self.checkpoint()
            return
        deadline = time.monotonic() + seconds
        while True:
            self.checkpoint()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            self.stop_event.wait(min(remaining, 1.0))


class LeaseKeeper:
    """Background heartbeat thread for one job (context manager)."""

    def __init__(
        self,
        heartbeat: Callable[[int, uuid.UUID], object],
        context: JobContext,
        interval_seconds: float,
        lease_duration_seconds: float,
        on_beat: Optional[Callable[[], None]] = None,
    ):
        if interval_seconds <= 0 or interval_seconds * 2 > lease_duration_seconds:
            raise ValueError("heartbeat interval must be > 0 and <= lease_duration / 2")
        self._heartbeat = heartbeat
        self.context = context
        self.interval = interval_seconds
        self.lease_duration = lease_duration_seconds
        self._on_beat = on_beat
        self._done = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self.last_success = time.monotonic()
        self.beats = 0

    def __enter__(self) -> "LeaseKeeper":
        self.last_success = time.monotonic()
        self._thread = threading.Thread(
            target=self._run, name=f"lease-keeper-{self.context.job_id}", daemon=True
        )
        self._thread.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()

    def stop(self) -> None:
        self._done.set()
        if self._thread is not None:
            self._thread.join(timeout=self.interval + 30)
            self._thread = None

    def _run(self) -> None:
        while not self._done.wait(self.interval):
            try:
                self._heartbeat(self.context.job_id, self.context.claim_token)
                self.last_success = time.monotonic()
                self.beats += 1
                if self._on_beat:
                    self._on_beat()
            except OwnershipLostError as e:
                logger.error("Lost ownership of job %s: %s", self.context.job_id, e)
                self.context.mark_lost(e)
                return
            except Exception as e:  # transient DB problem: keep trying until the lease would lapse
                elapsed = time.monotonic() - self.last_success
                logger.warning(
                    "Heartbeat for job %s failed (%.0fs since last success): %s",
                    self.context.job_id, elapsed, e,
                )
                if elapsed >= self.lease_duration:
                    self.context.mark_lost(
                        LeaseExpiredError(
                            f"Job {self.context.job_id}: no successful heartbeat for {elapsed:.0f}s "
                            f"(lease {self.lease_duration}s); assuming lease expired"
                        )
                    )
                    return

