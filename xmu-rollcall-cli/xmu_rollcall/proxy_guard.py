"""Utilities for disabling inherited proxy settings."""


def disable_system_proxies():
    import requests.sessions

    if getattr(requests.sessions.Session, "_xmu_proxy_patched", False):
        return

    original_init = requests.sessions.Session.__init__

    def patched_init(self, *args, **kwargs):
        original_init(self, *args, **kwargs)
        self.trust_env = False
        self.proxies = {}

    requests.sessions.Session.__init__ = patched_init
    requests.sessions.Session._xmu_proxy_patched = True
