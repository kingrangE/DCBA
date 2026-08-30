from app.services.kanana_generator import DEFAULT_KANANA_MODEL, KananaGenerator


def test_kanana_generator_uses_public_model_by_default(monkeypatch):
    monkeypatch.delenv("KANANA_MODEL_NAME", raising=False)

    generator = KananaGenerator()

    assert generator.model_name == DEFAULT_KANANA_MODEL
    assert generator._model is None


def test_kanana_generator_reads_generation_options(monkeypatch):
    monkeypatch.setenv("KANANA_MAX_NEW_TOKENS", "128")
    monkeypatch.setenv("KANANA_TEMPERATURE", "0")
    monkeypatch.setenv("KANANA_TOP_P", "1")

    generator = KananaGenerator()

    assert generator.max_new_tokens == 128
    assert generator.temperature == 0
    assert generator.top_p == 1
