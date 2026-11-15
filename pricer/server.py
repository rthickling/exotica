"""Single-process, serial HTTP boundary; no institutional credentials needed."""

import json
import os
import re
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

from engine import ValidationError, replay, value

MAX_BODY = 128 * 1024


class Handler(BaseHTTPRequestHandler):
    server_version = "SyntheticValuation/1"

    def send_json(self, code, result, correlation):
        payload = json.dumps(result, allow_nan=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Correlation-ID", correlation)
        self.end_headers()
        self.wfile.write(payload)

    def correlation(self):
        supplied = self.headers.get("X-Correlation-ID", "")
        result = (
            supplied
            if re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", supplied)
            else str(uuid.uuid4())
        )
        self._correlation = result
        return result

    def do_GET(self):
        correlation = self.correlation()
        self.send_json(
            200 if self.path == "/health" else 404,
            {"status": "UP", "serialWorker": True}
            if self.path == "/health"
            else {"error": "NOT_FOUND", "message": "Unknown endpoint"},
            correlation,
        )

    def do_POST(self):
        correlation = self.correlation()
        try:
            if self.path not in ("/value", "/replay"):
                self.send_json(
                    404,
                    {"error": "NOT_FOUND", "message": "Unknown endpoint"},
                    correlation,
                )
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError as exc:
                raise ValidationError("Invalid Content-Length") from exc
            if not 0 < size <= MAX_BODY:
                self.send_json(
                    413,
                    {
                        "error": "BODY_SIZE",
                        "message": f"Body must be 1..{MAX_BODY} bytes",
                    },
                    correlation,
                )
                return
            self.connection.settimeout(10)
            request = json.loads(
                self.rfile.read(size),
                parse_constant=lambda x: (_ for _ in ()).throw(
                    ValueError("Nonfinite JSON number")
                ),
            )
            result = value(request) if self.path == "/value" else replay(request)
            self.send_json(200, result, correlation)
        except (ValidationError, ValueError, TypeError) as exc:
            self.send_json(
                400,
                {
                    "error": "INVALID_REQUEST",
                    "message": str(exc),
                    "correlationId": correlation,
                },
                correlation,
            )
        except Exception:
            self.send_json(
                500,
                {
                    "error": "PRICING_FAILED",
                    "message": "Valuation failed; inspect service logs",
                    "correlationId": correlation,
                },
                correlation,
            )
            raise

    def log_message(self, fmt, *args):
        print(
            json.dumps(
                {
                    "component": "pricer",
                    "correlationId": getattr(self, "_correlation", None),
                    "message": fmt % args,
                }
            ),
            flush=True,
        )


if __name__ == "__main__":
    HTTPServer(
        (os.getenv("PRICER_BIND", "0.0.0.0"), int(os.getenv("PRICER_PORT", "8090"))),
        Handler,
    ).serve_forever()
