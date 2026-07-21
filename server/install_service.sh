#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
APP_DIR="$(pwd)"
APP_USER="$(id -un)"

if [ ! -d ".venv" ]; then
  python3 -m venv .venv
fi

source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt

SERVICE_FILE="/tmp/lifeos.service"

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Life OS
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${APP_DIR}
ExecStart=${APP_DIR}/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo cp "$SERVICE_FILE" /etc/systemd/system/lifeos.service
sudo systemctl daemon-reload
sudo systemctl enable --now lifeos.service
sudo systemctl status lifeos.service --no-pager
