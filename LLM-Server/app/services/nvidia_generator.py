import os
from urllib.parse import urlsplit

from openai import OpenAI
from openai.types.chat import ChatCompletion


class NvidiaGenerator:
    def configure(self) -> None:
        api_key = os.getenv("NVIDIA_API_KEY", "").strip()
        self.model = os.getenv("NVIDIA_MODEL", "").strip()
        base_url = os.getenv("NVIDIA_BASE_URL") or "https://integrate.api.nvidia.com/v1"
        if not api_key or not self.model:
            raise ValueError("NVIDIA_API_KEY and NVIDIA_MODEL are required for generation")
        endpoint = urlsplit(base_url)
        if endpoint.scheme != "https" or not endpoint.hostname or endpoint.username is not None:
            raise ValueError("NVIDIA_BASE_URL must be an HTTPS URL without credentials")

        # The Redis queue already retries transient failures.
        self._client = OpenAI(api_key=api_key, base_url=base_url, timeout=30.0, max_retries=0)

    def close(self) -> None:
        self._client.close()

    def generate(self, messages: list[dict[str, str]], *, timeout: float | None = None, on_usage=None) -> str:
        is_nemotron_ultra = self.model == "nvidia/nemotron-3-ultra-550b-a55b"
        client = self._client if timeout is None else self._client.with_options(timeout=timeout)
        response = client.chat.completions.create(
            model=self.model,
            messages=messages,
            max_tokens=1536,
            temperature=0.2,
            top_p=0.9,
            # Nemotron Ultra's default reasoning can exhaust the budget before emitting an answer.
            extra_body={"chat_template_kwargs": {"enable_thinking": False}} if is_nemotron_ultra else None,
        )
        if not isinstance(response, ChatCompletion):
            return ""
        prompt_tokens = getattr(response.usage, "prompt_tokens", None)
        if on_usage is not None and isinstance(prompt_tokens, int):
            on_usage(prompt_tokens)
        if not response.choices:
            return ""
        choice = response.choices[0]
        content = getattr(getattr(choice, "message", None), "content", None)
        if getattr(choice, "finish_reason", None) != "stop" or not isinstance(content, str):
            return ""
        return content.strip()
