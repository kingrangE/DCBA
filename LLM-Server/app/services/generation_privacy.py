import re
import unicodedata

# These checks cover recognizable identifiers, not arbitrary names or confidential prose.
EMAIL = re.compile(r"(?i)[\w.+-]+\s*@\s*[\w-]+(?:\s*\.\s*[\w-]+)+")
PHONE = re.compile(r"(?<![0-9])(?:\+?82[\s.-]*)?0?1[016789][\s.()‐‑–—-]*[0-9]{3,4}[\s.()‐‑–—-]*[0-9]{4}(?![0-9])")
UUID = re.compile(r"(?i)\b[0-9a-f]{8}(?:[\s‐‑–—-][0-9a-f]{4}){3}[\s‐‑–—-][0-9a-f]{12}\b")
LABEL = re.compile(r"(?i)(?:계정|사용자|사번|식별자|조직|고객|user|account|employee|tenant|org)\s*(?:id|번호|코드)?\s*[:=]\s*([a-z0-9][a-z0-9_.@+/-]{1,63})")
HANDLE = re.compile(r"(?i)\b(?:qa|test|demo|acct|account|user|tenant|org|employee|customer|cust|private|corp|staff|id)[\W_]{0,8}[0-9]{1,12}\b")
LOCAL_HOST = re.compile(r"(?i)\b[\w-]+(?:\.[\w-]+)*\.(?:internal|corp|local)\b")


def normalize(text):
    return "".join(character for character in unicodedata.normalize("NFKC", text).casefold()
                   if unicodedata.category(character) != "Cf")


def identifier_fragments(text):
    text = normalize(text)
    return {match.group(0) for pattern in (EMAIL, PHONE, UUID, HANDLE, LOCAL_HOST) for match in pattern.finditer(text)} | {
        match.group(1) for match in LABEL.finditer(text)
    }


def contains_identifier(text):
    return bool(identifier_fragments(text))


def public_text(values, intent=None):
    text = "\n".join(values)
    if contains_identifier(text):
        return False
    if not intent:
        return True
    # Match long copied excerpts after case, width, whitespace, and punctuation normalization.
    words = re.findall(r"[^\W_]+", normalize(intent))
    compact = "".join(re.findall(r"[^\W_]+", normalize(text)))
    for index in range(len(words) - 2):
        fragment = "".join(words[index:index + 3])
        if len(fragment) >= 16 and fragment in compact:
            return False
    return True
