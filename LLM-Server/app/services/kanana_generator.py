import os
import threading
from typing import Any


DEFAULT_KANANA_MODEL = "kakaocorp/kanana-2-3b-instruct"


class KananaGenerator:
    """Lazy-loading local text generator for Kanana-2."""

    def __init__(
        self,
        model_name: str | None = None,
        device_map: str | None = None,
        max_new_tokens: int | None = None,
        temperature: float | None = None,
        top_p: float | None = None,
    ):
        self.model_name = model_name or os.getenv(
            "KANANA_MODEL_NAME", DEFAULT_KANANA_MODEL
        )
        self.device_map = device_map or os.getenv("KANANA_DEVICE_MAP", "auto")
        self.max_new_tokens = max_new_tokens or int(
            os.getenv("KANANA_MAX_NEW_TOKENS", "512")
        )
        self.temperature = (
            temperature
            if temperature is not None
            else float(os.getenv("KANANA_TEMPERATURE", "0.2"))
        )
        self.top_p = top_p if top_p is not None else float(
            os.getenv("KANANA_TOP_P", "0.9")
        )

        self._tokenizer: Any = None
        self._model: Any = None
        self._torch: Any = None
        self._lock = threading.Lock()

    def _load_model(self) -> None:
        if self._model is not None:
            return

        try:
            import torch
            from transformers import AutoModelForCausalLM, AutoTokenizer
        except ImportError as exc:
            raise RuntimeError(
                "Kanana 실행 패키지가 없습니다. "
                "pip install -r requirements.txt를 다시 실행해주세요."
            ) from exc

        print(f"[Kanana] Loading model: {self.model_name}")
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_name)
        self._model = AutoModelForCausalLM.from_pretrained(
            self.model_name,
            dtype="auto",
            device_map=self.device_map,
            low_cpu_mem_usage=True,
        )
        self._model.eval()
        self._torch = torch
        print(f"[Kanana] Model loaded: {self.model_name}")

    def generate(self, messages: list[dict[str, str]]) -> str:
        # Loading and generation are serialized because the same model instance is shared
        # by the scheduler and the HTTP endpoint.
        with self._lock:
            self._load_model()

            model_inputs = self._tokenizer.apply_chat_template(
                messages,
                tokenize=True,
                add_generation_prompt=True,
                return_tensors="pt",
                return_dict=True,
                thinking_mode="no_think",
            )
            input_device = self._model.get_input_embeddings().weight.device
            model_inputs = model_inputs.to(input_device)

            generation_options = {
                "max_new_tokens": self.max_new_tokens,
                "do_sample": self.temperature > 0,
                "pad_token_id": self._tokenizer.pad_token_id,
                "eos_token_id": self._tokenizer.eos_token_id,
            }
            if self.temperature > 0:
                generation_options.update(
                    temperature=self.temperature,
                    top_p=self.top_p,
                )

            input_length = model_inputs["input_ids"].shape[-1]
            with self._torch.inference_mode():
                output_ids = self._model.generate(
                    **model_inputs,
                    **generation_options,
                )

            generated_ids = output_ids[0][input_length:]
            return self._tokenizer.decode(
                generated_ids,
                skip_special_tokens=True,
            ).strip()
