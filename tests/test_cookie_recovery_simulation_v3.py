"""Corrupted cache and disk-write fault recovery using synthetic credentials."""
import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall import config, utils


def active_session():
    session = requests.Session()
    session.cookies.set("active", "synthetic-active", domain="127.0.0.1", path="/")
    return session


@pytest.mark.parametrize("cache_format", ["legacy", "versioned"])
@pytest.mark.parametrize("field", ["name", "value"])
@pytest.mark.parametrize("newline", ["\r", "\n"])
def test_header_breaking_cookie_cache_does_not_replace_the_active_jar(tmp_path, cache_format, field, newline):
    with active_session() as source, active_session() as target:
        if cache_format == "versioned":
            payload = utils._cookie_cache_payload(source.cookies)
            payload["cookies"].append(dict(payload["cookies"][0], name="other", value="synthetic-other"))
            payload["cookies"][-1][field] += newline + "invalid"
        else:
            name = "other" + (newline + "invalid" if field == "name" else "")
            value = "synthetic-other" + (newline + "invalid" if field == "value" else "")
            payload = {"valid-first": "synthetic-valid", name: value}
        path = tmp_path / "cookies.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        original_jar = target.cookies
        with patch.object(utils, "_diag_log") as diagnostic:
            assert utils.load_session(target, str(path)) is False
        assert target.cookies is original_jar
        prepared = target.prepare_request(requests.Request("GET", "http://127.0.0.1/"))
        assert prepared.headers["Cookie"] == "active=synthetic-active"
        diagnostic.assert_called_once()


@pytest.mark.parametrize("raw,decrypted", [('{"version":2,"cookies":[', None), ("dpapi:synthetic", "")])
def test_truncated_or_undecryptable_cache_can_be_replaced_by_the_next_valid_save(tmp_path, raw, decrypted):
    path = tmp_path / "cookies.json"
    path.write_text(raw, encoding="utf-8")
    with active_session() as source, active_session() as restored:
        original_jar = restored.cookies
        with patch.object(utils, "_diag_log"), patch.object(utils.secrets, "unprotect", return_value=decrypted):
            assert utils.load_session(restored, str(path)) is False
        assert restored.cookies is original_jar
        utils.save_session(source, str(path))
        assert utils.load_session(restored, str(path)) is True
        assert restored.cookies.get("active") == "synthetic-active"
        assert not Path(str(path) + ".tmp").exists()


def test_cookie_cache_replace_failure_retains_the_last_restorable_session(tmp_path):
    path = tmp_path / "cookies.json"
    with active_session() as source, active_session() as restored:
        utils.save_session(source, str(path))
        previous = path.read_bytes()
        source.cookies.set("active", "synthetic-new", domain="127.0.0.1", path="/")
        with patch.object(utils.os, "replace", side_effect=PermissionError("simulated file lock")), \
                patch.object(utils, "_diag_log") as diagnostic:
            utils.save_session(source, str(path))
        assert path.read_bytes() == previous
        assert not Path(str(path) + ".tmp").exists()
        diagnostic.assert_called_once()
        assert utils.load_session(restored, str(path)) is True
        assert restored.cookies.get("active") == "synthetic-active"
        utils.save_session(source, str(path))
        assert utils.load_session(restored, str(path)) is True
        assert restored.cookies.get("active") == "synthetic-new"


def test_config_disk_full_during_serialization_keeps_previous_profile(tmp_path):
    path = tmp_path / "config.json"
    pending = tmp_path / "config.json.tmp"
    original_open = open

    class DiskFullFile:
        def __init__(self, file):
            self.file = file

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return self.file.__exit__(*args)

        def write(self, data):
            self.file.write(data[:1])
            self.file.flush()
            raise OSError("simulated disk full")

    def failing_open(filename, mode="r", *args, **kwargs):
        file = original_open(filename, mode, *args, **kwargs)
        return DiskFullFile(file) if Path(filename) == pending and mode == "w" else file

    with patch.object(config, "CONFIG_DIR", tmp_path), patch.object(config, "CONFIG_FILE", path):
        config.save_config({"accounts": [], "simulation": "before"})
        previous = path.read_bytes()
        with patch("builtins.open", failing_open), pytest.raises(OSError, match="disk full"):
            config.save_config({"accounts": [], "simulation": "after"})
        assert path.read_bytes() == previous
        assert not pending.exists()
        assert config.load_config()["simulation"] == "before"
        config.save_config({"accounts": [], "simulation": "after"})
        assert config.load_config()["simulation"] == "after"
