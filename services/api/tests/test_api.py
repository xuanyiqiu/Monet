from pathlib import Path

import yaml

from tests.conftest import AUTH

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "openapi.yaml"


def test_health_is_public(client):
    r = client.get("/v1/health")
    assert r.status_code == 200
    assert r.json()["llm_provider"] == "fake"


def test_auth_required_and_problem_json(client):
    r = client.post("/v1/annotate", json={"text": "你好", "target_lang": "en"})
    assert r.status_code == 401
    assert r.headers["content-type"].startswith("application/problem+json")
    assert r.json()["status"] == 401


def test_bad_dev_token(client):
    r = client.get("/v1/me", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401


def test_me(client):
    assert client.get("/v1/me", headers=AUTH).json() == {"user_id": "tester"}


def test_annotate_endpoint(client):
    r = client.post("/v1/annotate", headers=AUTH, json={"text": "他真是个显眼包", "target_lang": "es"})
    assert r.status_code == 200
    body = r.json()
    assert body["source_lang"] == "zh"
    assert body["expressions"][0]["kind"] == "meme"
    assert body["expressions"][0]["equivalent"] == "el payaso del grupo"


def test_annotate_validation_error(client):
    r = client.post("/v1/annotate", headers=AUTH, json={"text": "", "target_lang": "fr"})
    assert r.status_code == 422
    assert r.headers["content-type"].startswith("application/problem+json")


def test_annotate_whitespace_only(client):
    r = client.post("/v1/annotate", headers=AUTH, json={"text": "   ", "target_lang": "en"})
    assert r.status_code == 422


def test_lines_endpoint(client):
    r = client.post(
        "/v1/annotate/lines",
        headers=AUTH,
        json={"lines": [{"id": "1", "text": "no cap"}], "target_lang": "zh"},
    )
    assert r.status_code == 200
    assert r.json()["lines"][0]["expressions"][0]["text"] == "no cap"


def test_feedback(client):
    r = client.post(
        "/v1/feedback", headers=AUTH, json={"text": "绝绝子", "target_lang": "en", "comment": "bad"}
    )
    assert r.status_code == 202


def test_rate_limit(settings, fake_llm):
    from fastapi.testclient import TestClient

    from monet_api.main import create_app

    c = TestClient(create_app(settings.model_copy(update={"rate_limit_per_minute": 2}), llm=fake_llm))
    codes = [
        c.post("/v1/feedback", headers=AUTH, json={"text": "x", "target_lang": "en"}).status_code
        for _ in range(3)
    ]
    assert codes == [202, 202, 429]


def test_implemented_routes_match_contract(client):
    """Every implemented route must exist in contracts/openapi.yaml with the same operationId,
    and shared schemas must declare the same properties."""
    contract = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    live = client.app.openapi()
    for path, ops in live["paths"].items():
        assert path in contract["paths"], f"{path} not in contract"
        for method, op in ops.items():
            assert method in contract["paths"][path], f"{method.upper()} {path} not in contract"
            assert op["operationId"] == contract["paths"][path][method]["operationId"]
    for name, schema in live["components"]["schemas"].items():
        if name in contract["components"]["schemas"] and "properties" in schema:
            want = set(contract["components"]["schemas"][name].get("properties", {}))
            assert set(schema["properties"]) == want, f"schema {name} drifted from contract"


def test_playground_served_in_dev(client):
    r = client.get("/playground")
    assert r.status_code == 200
    assert "Monet" in r.text
