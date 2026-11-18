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

    def generate(self, messages: list[dict[str, str]]) -> str:
        is_nemotron_ultra = self.model == "nvidia/nemotron-3-ultra-550b-a55b"
        response = self._client.chat.completions.create(
            model=self.model,
            messages=messages,
            max_tokens=1024 if is_nemotron_ultra else 512,
            temperature=0.2,
            top_p=0.9,
            # Nemotron Ultra's default reasoning can exhaust the budget before emitting an answer.
            extra_body={"chat_template_kwargs": {"enable_thinking": False}} if is_nemotron_ultra else None,
        )
        if not isinstance(response, ChatCompletion) or not getattr(response, "choices", None):
            return ""
        choice = response.choices[0]
        content = getattr(getattr(choice, "message", None), "content", None)
        if getattr(choice, "finish_reason", None) != "stop" or not isinstance(content, str):
            return ""
        return content.strip()
