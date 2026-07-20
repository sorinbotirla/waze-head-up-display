#!/usr/bin/env python3
import argparse
import json
import mimetypes
import os
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

HOST = "127.0.0.1"
WEB_PORT = 8765
PHONE_PORT = 8766
ADB = os.environ.get("ADB", "adb")
ROOT = Path(__file__).resolve().parent
WEB_ROOT = ROOT / "web"

def run_adb(*args):
    try:
        p = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=15)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except FileNotFoundError:
        return 127, "", "adb not found. Add Android platform-tools to PATH."
    except Exception as exc:
        return 1, "", str(exc)

def selected_device():
    code, out, err = run_adb("devices")
    if code != 0:
        return None, err
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    if not devices:
        return None, "No authorized USB Android device detected."
    return devices[0], None

def ensure_forward():
    serial, err = selected_device()
    if not serial:
        return False, err
    code, out, ferr = run_adb("-s", serial, "forward", f"tcp:{PHONE_PORT}", f"tcp:{PHONE_PORT}")
    if code != 0:
        return False, ferr or out
    return True, serial

def proxy(method, path, payload=None):
    url = f"http://127.0.0.1:{PHONE_PORT}{path}"
    body = None
    headers = {}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return response.status, raw
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")
    except Exception as exc:
        return 502, json.dumps({"ok": False, "error": str(exc)})

class Handler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        clean = path.split("?", 1)[0].split("#", 1)[0]
        if clean == "/":
            clean = "/index.html"
        return str((WEB_ROOT / clean.lstrip("/")).resolve())

    def log_message(self, fmt, *args):
        sys.stdout.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    def send_json(self, status, obj):
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b"{}"
        return json.loads(raw.decode("utf-8"))

    def do_GET(self):
        if self.path.startswith("/api/device"):
            ok, detail = ensure_forward()
            self.send_json(200 if ok else 503, {"ok": ok, "device": detail if ok else None, "error": None if ok else detail})
            return
        if self.path.startswith("/api/status"):
            ok, detail = ensure_forward()
            if not ok:
                self.send_json(503, {"ok": False, "error": detail})
                return
            status, raw = proxy("GET", "/status")
            try:
                obj = json.loads(raw)
            except Exception:
                obj = {"ok": False, "error": raw}
            self.send_json(status, obj)
            return
        return super().do_GET()

    def do_POST(self):
        routes = {
            "/api/route": ("/route", True),
            "/api/play": ("/play", False),
            "/api/pause": ("/pause", False),
            "/api/stop": ("/stop", False),
            "/api/seek": ("/seek", True),
        }
        path = self.path.split("?", 1)[0]
        if path not in routes:
            self.send_json(404, {"ok": False, "error": "Unknown API path"})
            return

        ok, detail = ensure_forward()
        if not ok:
            self.send_json(503, {"ok": False, "error": detail})
            return

        phone_path, needs_body = routes[path]
        payload = self.read_json() if needs_body else {}
        status, raw = proxy("POST", phone_path, payload)
        try:
            obj = json.loads(raw)
        except Exception:
            obj = {"ok": False, "error": raw}
        self.send_json(status, obj)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=WEB_PORT)
    args = parser.parse_args()

    ok, detail = ensure_forward()
    if ok:
        print(f"ADB connected to {detail}; forwarded PC tcp:{PHONE_PORT} -> phone tcp:{PHONE_PORT}")
    else:
        print("ADB setup warning:", detail)

    server = ThreadingHTTPServer((HOST, args.port), Handler)
    print(f"Open http://{HOST}:{args.port}")
    print("Keep this terminal open while testing.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    main()
