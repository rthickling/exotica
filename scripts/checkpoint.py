"""Write a content-addressed source checkpoint, excluding generated evidence/builds."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {"build", ".gradle", ".git", ".venv", "__pycache__", "evidence"}
entries = {}
for path in sorted(ROOT.rglob("*")):
    if path.is_file() and not EXCLUDED.intersection(path.relative_to(ROOT).parts):
        entries[str(path.relative_to(ROOT))] = hashlib.sha256(
            path.read_bytes()
        ).hexdigest()
raw = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
result = {
    "algorithm": "sha256",
    "checkpoint": hashlib.sha256(raw).hexdigest(),
    "files": entries,
}
dest = ROOT / "evidence" / "checkpoint.json"
dest.parent.mkdir(exist_ok=True)
dest.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({"checkpoint": result["checkpoint"], "sourceFiles": len(entries)}))
