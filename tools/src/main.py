"""Process placeholder; no crawling, scheduling or document processing yet."""

import logging
import signal
import threading


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopped.set())
    logging.info("Tools scaffold started; waiting for shutdown.")
    stopped.wait()


if __name__ == "__main__":
    main()
