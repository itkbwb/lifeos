#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
mkdir -p backups

if [ ! -f "data/lifeos.db" ]; then
  echo "Database does not exist yet."
  exit 1
fi

stamp="$(date +%Y-%m-%d_%H-%M-%S)"
cp data/lifeos.db "backups/lifeos_${stamp}.db"
find backups -type f -name "lifeos_*.db" -mtime +30 -delete

echo "Backup created: backups/lifeos_${stamp}.db"
