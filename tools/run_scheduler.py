#!/usr/bin/env python3
"""Scheduler entry point — runs the automatic job scheduler."""

import os
import sys

# Ensure tools/ is on sys.path so `src.scheduler` resolves cleanly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from src.scheduler import main

if __name__ == "__main__":
    main()
