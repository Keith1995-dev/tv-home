#!/usr/bin/env bash
# Start the phone remote + Claude assistant. Open the printed address on your phone (same Wi-Fi).
cd "$(dirname "$0")/.."
exec tools/venv/bin/python -I tools/remote/server.py
