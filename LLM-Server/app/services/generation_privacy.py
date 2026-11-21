import re
import unicodedata

# These checks cover recognizable identifiers, not arbitrary names or confidential prose.
EMAIL = re.compile(r"(?i)[\w.+-]+\s*@\s*[\w-]+(?:\s*\.\s*[\w-]+)+")
PHONE = re.compile(r"(?<![0-9])(?:\+?82[\s.-]*)?0?1[016789][\s.()‐‑–—-]*[0-9]{3,4}[\s.()‐‑–—-]*[0-9]{4}(?![0-9])")
UUID = re.compile(r"(?i)\b[0-9a-f]{8}(?:[\s‐‑–—-][0-9a-f]{4}){3}[\s‐‑–—-][0-9a-f]{12}\b")
LABEL = re.compile(r"(?i)(?:계정|사용자|사번|식별자|조직|고객|user|account|employee|tenant|org)\s*(?:id|번호|코드)?\s*[:=]\s*([a-z0-9][a-z0-9_.@+/-]{1,63})")
HANDLE = re.compile(r"(?i)\b(?:qa|test|demo|acct|account|user|tenant|org|employee|customer|cust|private|corp|staff|id)[\W_]{0,8}[0-9]{1,12}\b")
LOCAL_HOST = re.compile(r"(?i)\b[\w-]+(?:\.[\w-]+)*\.(?:internal|corp|local)\b")
SERIAL = re.compile(r"(?i)(?<![a-z0-9])([a-z]{2,24})([\s._·‐‑–—*-]{0,8})([0-9]{3,12})(?![a-z0-9])")
TECHNICAL_PREFIXES = {"aes", "ascii", "http", "ieee", "iso", "md", "rfc", "sha", "tls", "utf"}
PORT_SEPARATOR = r"[\s._·‐‑–—*-]{1,8}"
DEFAULT_PORT = re.compile(
    rf"(?<![a-z0-9])(?:postgresql{PORT_SEPARATOR}5432|redis{PORT_SEPARATOR}6379"
    rf"|mariadb{PORT_SEPARATOR}3306)(?![a-z0-9])"
)


def normalize(text):
    return "".join(character for character in unicodedata.normalize("NFKC", text).casefold()
                   if unicodedata.category(character) != "Cf")


def identifier_fragments(text):
    text = normalize(text)
    return {match.group(0) for pattern in (EMAIL, PHONE, UUID, HANDLE, LOCAL_HOST) for match in pattern.finditer(text)} | {
        match.group(1) for match in LABEL.finditer(text)
    } | {
        match.group(0) for match in SERIAL.finditer(text)
        if match.group(1) not in TECHNICAL_PREFIXES and not DEFAULT_PORT.fullmatch(match.group(0))
        and (not match.group(2).isspace() or len(match.group(3)) >= 5)
    }


def contains_identifier(text):
    return bool(identifier_fragments(text))


def compact(text):
    return "".join(re.findall(r"[^\W_]+", normalize(text)))


def private_fragments(intent):
    words = re.findall(r"[^\W_]+", DEFAULT_PORT.sub(" ", normalize(intent)))
    excerpts = {"".join(words[i:i + 3]) for i in range(len(words) - 2)}
    identifiers = identifier_fragments(intent)
    parts = {part for identifier in identifiers
             for part in re.findall(r"[^\W_]+", identifier) if len(part) >= 4}
    return {value for value in excerpts if len(value) >= 16} | {compact(value) for value in identifiers} | parts


def public_text(values, fragments=()):
    text = "\n".join(values)
    if contains_identifier(text):
        return False
    normalized = compact(DEFAULT_PORT.sub(" ", normalize(text)))
    return not any(fragment in normalized for fragment in fragments)
