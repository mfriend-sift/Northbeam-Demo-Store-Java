#!/usr/bin/env bash
# Clean-store grep test: no Sift-named code outside the allowed files.
# Allowed: public/demo-drawer.js (demo tooling: its feed copy names Sift), the DEMO-only demo route files
# (demo.js, demo.py, demo.rb, demo_controller.rb, Demo.php, DemoApi.java; /demo/reset reads the mock URL env var), contract/ (frozen),
# README.md, tools/run.sh, preflight.sh, instrument.sh, contract-test.mjs,
# and this script. Dependencies, venvs, and git data are skipped.
cd "$(dirname "$0")/.." || exit 2
hits=$(grep -rniI sift . \
  --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=.venv --exclude-dir=__pycache__ --exclude-dir=vendor --exclude-dir=.bundle --exclude-dir=bin --exclude-dir=obj --exclude-dir=target --exclude-dir=.mvn --exclude-dir=.worktrees --exclude-dir=contract \
  --exclude=README.md --exclude=run.sh --exclude=preflight.sh --exclude=instrument.sh --exclude=contract-test.mjs --exclude=grep-test.sh \
  --exclude=demo-drawer.js --exclude=demo.js --exclude=demo.py --exclude=demo.rb --exclude=demo_controller.rb --exclude=Demo.php --exclude=DemoApi.java \
  --exclude=package-lock.json --exclude=Gemfile.lock --exclude=composer.lock)
if [ -n "$hits" ]; then echo "FAIL grep test: Sift names found outside the allowed files"; echo "$hits"; exit 1; fi
echo "OK grep test: no Sift names outside the allowed files"
