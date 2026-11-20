"""robots.txt parser and checker.

Implements robots.txt parsing per requirements:
- Respect robots.txt directives
- User-agent matching
- Crawl-delay parsing
- Disallow pattern matching
"""

import logging
from typing import Optional
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

from .exceptions import RobotsDisallowedError

logger = logging.getLogger(__name__)


class RobotsChecker:
    """Robots.txt checker with caching."""

    def __init__(self, user_agent: str):
        """Initialize robots checker.

        Args:
            user_agent: User agent string to use for robots.txt matching
        """
        self.user_agent = user_agent
        self.parsers: dict[str, Optional[RobotFileParser]] = {}

    def _get_robots_url(self, url: str) -> str:
        """Get robots.txt URL for given URL.

        Args:
            url: URL to get robots.txt for

        Returns:
            robots.txt URL
        """
        parsed = urlparse(url)
        robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
        return robots_url

    def _get_parser(self, url: str) -> Optional[RobotFileParser]:
        """Get or create robots.txt parser for domain.

        Args:
            url: URL to get parser for

        Returns:
            RobotFileParser or None if robots.txt not accessible
        """
        robots_url = self._get_robots_url(url)

        # Check cache
        if robots_url in self.parsers:
            return self.parsers[robots_url]

        # Create and fetch parser
        parser = RobotFileParser()
        parser.set_url(robots_url)

        try:
            logger.debug(f"Fetching robots.txt: {robots_url}")
            parser.read()
            self.parsers[robots_url] = parser
            logger.info(f"Loaded robots.txt from {robots_url}")
            return parser
        except Exception as e:
            # If robots.txt not accessible, assume allowed
            logger.warning(
                f"Could not fetch robots.txt from {robots_url}: {e}. Assuming allowed."
            )
            self.parsers[robots_url] = None
            return None

    def is_allowed(self, url: str) -> bool:
        """Check if URL is allowed by robots.txt.

        Args:
            url: URL to check

        Returns:
            True if allowed, False if disallowed

        Note:
            If robots.txt is not accessible, assumes allowed.
        """
        parser = self._get_parser(url)

        if parser is None:
            # robots.txt not accessible, assume allowed
            return True

        allowed = parser.can_fetch(self.user_agent, url)
        if not allowed:
            logger.info(f"URL disallowed by robots.txt: {url}")

        return allowed

    def get_crawl_delay(self, url: str) -> Optional[float]:
        """Get crawl delay from robots.txt.

        Args:
            url: URL to get crawl delay for

        Returns:
            Crawl delay in seconds, or None if not specified
        """
        parser = self._get_parser(url)

        if parser is None:
            return None

        try:
            delay = parser.crawl_delay(self.user_agent)
            if delay:
                logger.debug(f"Crawl delay for {url}: {delay}s")
            return delay
        except Exception:
            return None

    def check_and_raise(self, url: str) -> None:
        """Check if URL is allowed and raise exception if not.

        Args:
            url: URL to check

        Raises:
            RobotsDisallowedError: If URL is disallowed by robots.txt
        """
        if not self.is_allowed(url):
            raise RobotsDisallowedError(f"URL disallowed by robots.txt: {url}")
