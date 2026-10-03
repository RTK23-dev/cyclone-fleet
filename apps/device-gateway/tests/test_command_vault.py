"""Plan 33 (C1): the vault store keeps ciphertext only, checks shapes, and keeps versions and the audit honest."""
from __future__ import annotations

import base64
import os
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError

TOKEN = "t" * 32


def b64(n: int) -> str:
    return base64.b64encode(os.urandom(n)).decode()


def sealed(n: int = 48) -> dict:
    return {"iv": b64(12), "ct": b64(n)}


INIT = {"kdf": {"name": "PBKDF2", "hash": "SHA-256", "iterations": 600_000}, "salt": b64(16),
        "wrappedVk": sealed(), "recoveryWrappedVk": sealed()}


def item(item_id: str = "vi_abcdefghijklmnop", version: int = 1, **extra) -> dict:
    return {"id": item_id, "accountId": None, "kind": "login", "iv": b64(12), "ct": b64(200), "wrappedKey": sealed(),
            "version": version, **extra}


@pytest.fixture()
def center(tmp_path: Path):
    c = CommandCenter(tmp_path / "cc.db", contract=None, devices=lambda: [])
    yield c
    c.stop()


def test_a_vault_is_made_once_with_a_strong_kdf(center):
    assert center.vault.get() == {"exists": False, "format": 1, "minIterations": 600_000}
    weak = {**INIT, "kdf": {"name": "PBKDF2", "hash": "SHA-256", "iterations": 1000}}
    with pytest.raises(CommandError):
        center.vault.init(weak)
    with pytest.raises(CommandError):
        center.vault.init({**INIT, "salt": b64(8)})
    with pytest.raises(CommandError):
        center.vault.init({**INIT, "passphrase": "hunter2"})
    made = center.vault.init(INIT)
    assert made["exists"] and made["meta"]["keyVersion"] == 1
    with pytest.raises(CommandError):
        center.vault.init(INIT)


def test_items_are_ciphertext_with_monotonic_versions(center):
    center.vault.init(INIT)
    stored = center.vault.put_item(item())
    assert stored["version"] == 1 and stored["createdBy"] == "owner"
    with pytest.raises(CommandError):
        center.vault.put_item(item(version=1))  # stale write
    center.vault.put_item(item(version=2))
    with pytest.raises(CommandError):
        center.vault.put_item(item(version=3, kind="note"))  # kind is fixed
    with pytest.raises(CommandError):
        center.vault.put_item(item("vi_newitem00000000000", version=2))  # new items start at 1
    with pytest.raises(CommandError):
        center.vault.put_item({**item("vi_other0000000000000"), "password": "hunter2"})
    with pytest.raises(CommandError):
        center.vault.put_item({**item("vi_other0000000000000"), "ct": "not base64!"})
    with pytest.raises(CommandError):
        center.vault.put_item({**item("vi_other0000000000000"), "wrappedKey": {"iv": b64(12), "ct": b64(4)}})
    assert len(center.vault.get()["items"]) == 1


def test_items_link_to_accounts_and_block_account_removal(center):
    center.vault.init(INIT)
    acc = center.create_account({"service": "example.com", "handle": "me@example.com", "ownerBasis": "mine"})
    center.vault.put_item(item(accountId=acc["id"]))
    assert center.get_account(acc["id"])["vaultItems"] == 1
    with pytest.raises(CommandError):
        center.delete_account(acc["id"])
    with pytest.raises(CommandError):
        center.vault.put_item(item("vi_zzzzzzzzzzzzzzzzzz", accountId="acc_doesnotexist"))


def test_rewrap_changes_only_the_wrapped_key(center):
    center.vault.init(INIT)
    center.vault.put_item(item())
    before = center.vault.get()
    after = center.vault.rewrap({"kdf": INIT["kdf"], "salt": b64(16), "wrappedVk": sealed(), "keyVersion": 1})
    assert after["meta"]["keyVersion"] == 2 and after["items"] == before["items"]
    with pytest.raises(CommandError):
        center.vault.rewrap({"recoveryWrappedVk": sealed(), "keyVersion": 1})  # stale


def test_backup_restores_into_an_empty_vault_only(tmp_path: Path, center):
    center.vault.init(INIT)
    center.vault.put_item(item())
    center.vault.put_item(item(version=2))
    backup = center.vault.get()
    with pytest.raises(CommandError):
        center.vault.restore({"backup": backup})
    other = CommandCenter(tmp_path / "other.db", contract=None, devices=lambda: [])
    try:
        restored = other.vault.restore({"backup": backup})
        assert restored["items"][0]["ct"] == backup["items"][0]["ct"]
        assert restored["items"][0]["version"] == 2
        assert restored["meta"]["wrappedVk"] == backup["meta"]["wrappedVk"]
        with pytest.raises(CommandError):
            other.vault.restore({"backup": {"exists": True, "format": 99}})
    finally:
        other.stop()


def test_a_broken_backup_leaves_nothing_behind(tmp_path: Path):
    other = CommandCenter(tmp_path / "other.db", contract=None, devices=lambda: [])
    try:
        bad = {"exists": True, "format": 1, "meta": INIT, "items": [item(), {"id": "../x"}]}
        with pytest.raises(CommandError):
            other.vault.restore({"backup": bad})
        assert other.vault.get()["exists"] is False
    finally:
        other.stop()


def test_reset_needs_the_words_and_is_audited(center):
    center.vault.init(INIT)
    center.vault.put_item(item())
    with pytest.raises(CommandError):
        center.vault.reset({"confirm": "yes"})
    assert center.vault.reset({"confirm": "DELETE VAULT"})["exists"] is False
    actions = [e["action"] for e in center.audit()["entries"]]
    assert "vault.reset" in actions and "vault.item.create" in actions
    assert center.verify_audit()


def test_client_audit_takes_names_only(center):
    center.vault.client_audit({"action": "reveal", "itemId": "vi_abcdefghijklmnop"})
    with pytest.raises(CommandError):
        center.vault.client_audit({"action": "reveal", "value": "hunter2"})
    with pytest.raises(CommandError):
        center.vault.client_audit({"action": "anything"})


def test_vault_routes_need_the_bearer(center):
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    assert client.get("/v1/cc/vault").status_code == 401
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.post("/v1/cc/vault/init", json=INIT, headers=headers).status_code == 200
    assert client.post("/v1/cc/vault/items", json=item(), headers=headers).status_code == 200
    batch = client.post("/v1/cc/vault/import", json={"items": [item("vi_imported0000000001")]}, headers=headers)
    assert batch.status_code == 200 and batch.json()["items"][0]["createdBy"] == "import"
    assert client.get("/v1/cc/vault", headers=headers).json()["items"][0]["kind"] == "login"
