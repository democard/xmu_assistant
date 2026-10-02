"""Explicit ownership for detached sessions used by background workers."""

from contextlib import ExitStack
import threading


def close_cloned_session(session, source) -> None:
    """Release an owned clone without closing borrowed sessions or test doubles."""
    if session is not None and session is not source:
        close = getattr(session, "close", None)
        if callable(close):
            close()


class ThreadLocalSessions:
    """Reuse one clone per thread and release every clone after the pool exits.

    Enter this context before the executor, so executor shutdown joins all
    workers before any session's connection pool is closed. The clone factory
    remains supplied by the caller, including for fake-session adapters.
    """

    def __init__(self, source, clone):
        self._source = source
        self._clone = clone
        self._local = threading.local()
        self._cleanup = ExitStack()
        self._lock = threading.Lock()

    def get(self):
        session = getattr(self._local, "session", None)
        if session is None:
            session = self._clone(self._source)
            self._local.session = session
            with self._lock:
                self._cleanup.callback(close_cloned_session, session, self._source)
        return session

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        return self._cleanup.__exit__(*exception)
