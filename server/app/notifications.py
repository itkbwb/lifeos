from __future__ import annotations

import logging
import os

from app import models
from app.database import SessionLocal

logger = logging.getLogger("lifeos.notifications")

_SERVICE_ACCOUNT_FILE = os.environ.get("FCM_SERVICE_ACCOUNT_FILE")
_firebase_app = None
_firebase_init_attempted = False


def ensure_firebase():
    """Lazily initializes the Firebase app from FCM_SERVICE_ACCOUNT_FILE.
    Returns None (never raises) if the env var is unset or the file doesn't
    exist - this is how local/dev/test/CI runs work without any Firebase
    project having been created yet (see chapter: notifications, server-
    pushed)."""
    global _firebase_app, _firebase_init_attempted
    if _firebase_app is not None:
        return _firebase_app
    if _firebase_init_attempted:
        return None
    _firebase_init_attempted = True

    if not _SERVICE_ACCOUNT_FILE or not os.path.exists(_SERVICE_ACCOUNT_FILE):
        logger.info("FCM_SERVICE_ACCOUNT_FILE not set or missing - push notifications disabled")
        return None

    import firebase_admin
    from firebase_admin import credentials

    cred = credentials.Certificate(_SERVICE_ACCOUNT_FILE)
    _firebase_app = firebase_admin.initialize_app(cred)
    return _firebase_app


def send_push(title: str, body: str, data: dict) -> int:
    """Sends to every registered device token, pruning any token Firebase
    reports as invalid/unregistered. Returns how many sends succeeded (0 if
    FCM isn't configured, or there are no registered devices)."""
    app = ensure_firebase()
    if app is None:
        return 0

    from firebase_admin import messaging

    db = SessionLocal()
    try:
        tokens = [t.token for t in db.query(models.DeviceToken).all()]
        sent = 0
        for token in tokens:
            message = messaging.Message(
                notification=messaging.Notification(title=title, body=body),
                data={str(k): str(v) for k, v in data.items()},
                token=token,
            )
            try:
                messaging.send(message, app=app)
                sent += 1
            except Exception:  # noqa: BLE001 - any send failure means this token is dead, prune it
                logger.warning("FCM send failed for a device token, pruning it", exc_info=True)
                db.query(models.DeviceToken).filter(models.DeviceToken.token == token).delete()
        db.commit()
        return sent
    finally:
        db.close()
