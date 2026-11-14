#!/usr/bin/env bash
set -eu
cp -R "$(dirname "$0")/files/." .
git init -q 2>/dev/null || true
