from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from monet_api.annotator import Annotator
from monet_api.cache import MemoryCache
from monet_api.config import Settings
from monet_api.llm.fake import FakeProvider
from monet_api.main import create_app, shared_nlp

FIXTURES = Path(__file__).parent / "fixtures"
AUTH = {"Authorization": "Bearer dev:tester"}


@pytest.fixture(scope="session")
def settings() -> Settings:
    return Settings(
        env="test",
        llm_provider="fake",
        chengyu_path=FIXTURES / "chengyu.json",
        rate_limit_per_minute=1000,
        _env_file=None,
    )


@pytest.fixture(scope="session")
def nlp(settings):
    return shared_nlp(settings)


@pytest.fixture
def fake_llm() -> FakeProvider:
    return FakeProvider()


@pytest.fixture
def annotator(nlp, fake_llm) -> Annotator:
    lexicon, tokenizer = nlp
    return Annotator(lexicon, tokenizer, fake_llm, MemoryCache())


@pytest.fixture
def client(settings, fake_llm) -> TestClient:
    return TestClient(create_app(settings, llm=fake_llm))
