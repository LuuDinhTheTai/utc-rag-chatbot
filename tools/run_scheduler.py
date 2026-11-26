#!/usr/bin/env python3
"""
Scheduler entry point - runs the automatic job scheduler.

This scheduler creates crawl jobs periodically for enabled sources,
eliminating the need for manual job creation.
"""

import sys
import os

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

from src.scheduler import main

if __name__ == "__main__":
    main()
