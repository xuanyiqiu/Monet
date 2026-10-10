import time
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from monet_api import auth
from monet_api.main import create_app

SECRET = "x" * 48  # >= 32 bytes, like a real Supabase secret
URL = "https://abcd.supabase.co"


def claims(**over) -> dict:
    base = {"sub": "user-123", "aud": "authenticated", "exp": int(time.time()) + 600, "iss": f"{URL}/auth/v1"}
    base.update(over)
    return {k: v for k, v in base.items() if v is not None}


@pytest.fixture
def prod(settings, fake_llm):
    s = settings.model_copy(
        update={"auth_mode": "supabase", "supabase_jwt_secret": SECRET, "supabase_url": URL}
    )
    return TestClient(create_app(s, llm=fake_llm))


def me(client, token: str | None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return client.get("/v1/me", headers=headers)


def test_hs256_valid_token(prod):
    r = me(prod, jwt.encode(claims(), SECRET, algorithm="HS256"))
    assert r.status_code == 200 and r.json() == {"user_id": "user-123"}


@pytest.mark.parametrize(
    "bad",
    [
        claims(exp=int(time.time()) - 10),
        claims(aud="anon"),
        claims(sub=None),
        claims(exp=None),
    ],
    ids=["expired", "wrong-audience", "missing-sub", "missing-exp"],
)
def test_hs256_rejects_bad_claims(prod, bad):
    r = me(prod, jwt.encode(bad, SECRET, algorithm="HS256"))
    assert r.status_code == 401
    assert r.headers["content-type"].startswith("application/problem+json")


def test_hs256_rejects_wrong_signature_and_garbage(prod):
    assert me(prod, jwt.encode(claims(), "y" * 48, algorithm="HS256")).status_code == 401
    assert me(prod, "not-a-jwt").status_code == 401
    assert me(prod, None).status_code == 401


def test_dev_tokens_are_not_accepted_in_supabase_mode(prod):
    assert me(prod, "dev:tester").status_code == 401


def test_unsigned_and_unlisted_algorithms_are_rejected(prod):
    unsigned = jwt.encode(claims(), None, algorithm="none")
    assert me(prod, unsigned).status_code == 401
    assert me(prod, jwt.encode(claims(), SECRET, algorithm="HS512")).status_code == 401


def test_unconfigured_secret_is_a_server_error_not_an_open_door(settings, fake_llm):
    s = settings.model_copy(update={"auth_mode": "supabase", "supabase_jwt_secret": None})
    c = TestClient(create_app(s, llm=fake_llm))
    assert me(c, jwt.encode(claims(), SECRET, algorithm="HS256")).status_code == 500


@pytest.fixture
def es256(monkeypatch):
    private = ec.generate_private_key(ec.SECP256R1())
    fake_client = SimpleNamespace(
        get_signing_key_from_jwt=lambda token: SimpleNamespace(key=private.public_key())
    )
    monkeypatch.setattr(auth, "_jwks_client", lambda url: fake_client)
    return private


def test_es256_via_jwks(prod, es256):
    token = jwt.encode(claims(), es256, algorithm="ES256", headers={"kid": "k1"})
    assert me(prod, token).json() == {"user_id": "user-123"}


def test_es256_wrong_issuer_and_wrong_key(prod, es256):
    other = ec.generate_private_key(ec.SECP256R1())
    assert (
        me(prod, jwt.encode(claims(iss="https://evil.example/auth/v1"), es256, algorithm="ES256")).status_code
        == 401
    )
    assert me(prod, jwt.encode(claims(), other, algorithm="ES256")).status_code == 401


def test_es256_without_project_url_is_not_configured(settings, fake_llm, es256):
    s = settings.model_copy(
        update={"auth_mode": "supabase", "supabase_jwt_secret": SECRET, "supabase_url": None}
    )
    c = TestClient(create_app(s, llm=fake_llm))
    assert me(c, jwt.encode(claims(), es256, algorithm="ES256")).status_code == 500
