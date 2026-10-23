"""
Meisam VPN — Windows Standalone Client
Next-generation UI matching prototype in Design/meisam-vpn-design.html.
Networking, proxy, encryption, and tunneling logic 100% preserved.
"""

import sys
import os
import json
import uuid
import time
import ssl
import socket
import select
import base64
import ctypes
import winreg
import atexit
import threading
from urllib.parse import urlparse
import urllib.request
import webview

APP_NAME = "Meisam VPN"
APP_VERSION = "Version 3.5 (2026)"
LOCAL_PORT = 10809
DOMAINS = [
    "https://antpeak.com",
    "https://hibchr.com",
    "https://hisball.com",
    "https://bitphox.com",
    "https://freloop.com"
]

# Only the 7 genuine working VeePN free locations
FREE_REGIONS = [
    ("gb-lnd", "United Kingdom", "London"),
    ("nl", "Netherlands", "Amsterdam"),
    ("fr-prs", "France", "Paris"),
    ("us-va", "United States", "Virginia"),
    ("us-or", "United States", "Oregon"),
    ("sg", "Singapore", "Singapore"),
    ("ru-spb", "Russia", "St. Petersburg"),
]

REGION_CODE_MAP = {
    "gb-lnd": "gb-lnd",
    "united kingdom": "gb-lnd",
    "uk": "gb-lnd",
    "london": "gb-lnd",
    "nl": "nl",
    "netherlands": "nl",
    "amsterdam": "nl",
    "fr-prs": "fr-prs",
    "france": "fr-prs",
    "paris": "fr-prs",
    "us-va": "us-va",
    "virginia": "us-va",
    "us-or": "us-or",
    "oregon": "us-or",
    "united states": "us-va",
    "sg": "sg",
    "singapore": "sg",
    "ru-spb": "ru-spb",
    "russia": "ru-spb"
}

DEEP_TEST_URL = "https://web.telegram.org/"
CONFIG_FILE = "veepn_client_config.json"


# =========================================================================
# 1. Windows System Proxy Management (Preserved)
# =========================================================================
class WindowsProxyManager:
    INTERNET_SETTINGS = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
    INTERNET_OPTION_SETTINGS_CHANGED = 39
    INTERNET_OPTION_REFRESH = 37

    @classmethod
    def set_proxy(cls, server=f"127.0.0.1:{LOCAL_PORT}"):
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, cls.INTERNET_SETTINGS, 0, winreg.KEY_WRITE) as key:
                winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
                winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, server)
                winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, "<local>")
            cls._notify()
            return True
        except Exception as e:
            print("Error setting proxy:", e)
            return False

    @classmethod
    def disable_proxy(cls):
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, cls.INTERNET_SETTINGS, 0, winreg.KEY_WRITE) as key:
                winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 0)
            cls._notify()
            return True
        except Exception as e:
            print("Error disabling proxy:", e)
            return False

    @classmethod
    def _notify(cls):
        try:
            wininet = ctypes.windll.wininet
            wininet.InternetSetOptionW(0, cls.INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
            wininet.InternetSetOptionW(0, cls.INTERNET_OPTION_REFRESH, 0, 0)
        except Exception:
            pass


# =========================================================================
# 2. Central VeePN API Client (Preserved)
# =========================================================================
class VeePNApi:
    def __init__(self, config_file=CONFIG_FILE):
        self.config_file = config_file
        self.udid = None
        self.token = None
        self.token_expiry = 0
        self.active_domain = DOMAINS[0]
        self.load_config()

    def load_config(self):
        if os.path.exists(self.config_file):
            try:
                with open(self.config_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.udid = data.get("udid")
                    self.token = data.get("token")
                    self.token_expiry = data.get("expiry", 0)
            except Exception:
                pass
        if not self.udid:
            self.udid = str(uuid.uuid4())
            self.save_config()

    def save_config(self):
        try:
            existing = {}
            if os.path.exists(self.config_file):
                try:
                    with open(self.config_file, "r", encoding="utf-8") as f:
                        existing = json.load(f)
                except Exception:
                    pass
            existing.update({
                "udid": self.udid,
                "token": self.token,
                "expiry": self.token_expiry
            })
            with open(self.config_file, "w", encoding="utf-8") as f:
                json.dump(existing, f, indent=2)
        except Exception:
            pass

    def get_token(self):
        if self.token and time.time() < self.token_expiry - 3600:
            return self.token

        ctx = ssl.create_default_context()
        device_info = {
            "udid": self.udid,
            "appVersion": "4.0.2",
            "platform": "chrome",
            "platformVersion": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "timeZone": "Asia/Tehran",
            "deviceName": "Chrome 120.0.0.0"
        }

        for domain in DOMAINS:
            try:
                url = f"{domain}/api/launch/"
                req = urllib.request.Request(
                    url,
                    data=json.dumps(device_info).encode("utf-8"),
                    headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
                )
                with urllib.request.urlopen(req, timeout=6, context=ctx) as r:
                    res = json.loads(r.read().decode("utf-8"))
                    if res.get("success"):
                        self.token = res["data"]["accessToken"]
                        self.active_domain = domain
                        self.token_expiry = int(time.time()) + (25 * 86400)
                        self.save_config()
                        return self.token
            except Exception:
                continue
        raise Exception("عدم امکان ارتباط با سرورهای مرکزی VeePN")

    def get_servers_for_region(self, region="nl"):
        token = self.get_token()
        ctx = ssl.create_default_context()
        payload = {"protocol": "https", "region": region, "type": 0}

        req = urllib.request.Request(
            f"{self.active_domain}/api/server/list/",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}", "User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=8, context=ctx) as r:
            res = json.loads(r.read().decode("utf-8"))
            if res.get("success") and res.get("data"):
                return res["data"]
        raise Exception("لیست سرورها دریافت نشد")


# =========================================================================
# 3. Local SOCKS/HTTP Tunnel Server (Preserved with Traffic Counter)
# =========================================================================
class LocalTunnelServer:
    def __init__(self, remote_host, remote_port, username, password, local_port=LOCAL_PORT):
        self.remote_host = remote_host
        self.remote_port = remote_port
        self.username = username
        self.password = password
        self.local_port = local_port
        self.server_sock = None
        self.is_running = False
        self.total_bytes = 0

    def start(self):
        self.is_running = True
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind(("127.0.0.1", self.local_port))
        self.server_sock.listen(150)
        self.server_sock.settimeout(1.0)

        t = threading.Thread(target=self._accept_loop, daemon=True)
        t.start()
        return t

    def stop(self):
        self.is_running = False
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass

    def _accept_loop(self):
        while self.is_running:
            try:
                client_sock, _ = self.server_sock.accept()
                t = threading.Thread(target=self._handle_client, args=(client_sock,), daemon=True)
                t.start()
            except socket.timeout:
                continue
            except Exception:
                break

    def _handle_client(self, client_sock):
        remote_ssock = None
        try:
            req = b""
            while b"\r\n\r\n" not in req:
                chunk = client_sock.recv(4096)
                if not chunk:
                    break
                req += chunk

            if not req:
                client_sock.close()
                return

            first_line = req.split(b"\r\n")[0].decode("utf-8", errors="ignore")
            parts = first_line.split()
            if len(parts) < 2:
                client_sock.close()
                return

            method, target = parts[0], parts[1]

            ctx = ssl.create_default_context()
            raw_sock = socket.create_connection((self.remote_host, self.remote_port), timeout=10)
            remote_ssock = ctx.wrap_socket(raw_sock, server_hostname=self.remote_host)

            auth_str = base64.b64encode(f"{self.username}:{self.password}".encode("utf-8")).decode("utf-8")

            if method.upper() == "CONNECT":
                connect_cmd = (
                    f"CONNECT {target} HTTP/1.1\r\n"
                    f"Host: {target}\r\n"
                    f"Proxy-Authorization: Basic {auth_str}\r\n"
                    f"User-Agent: Mozilla/5.0\r\n"
                    f"Proxy-Connection: Keep-Alive\r\n\r\n"
                )
                remote_ssock.sendall(connect_cmd.encode("utf-8"))

                resp = b""
                while b"\r\n\r\n" not in resp:
                    chunk = remote_ssock.recv(4096)
                    if not chunk:
                        break
                    resp += chunk

                if b"200" in resp:
                    client_sock.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                else:
                    client_sock.sendall(resp)
                    return
            else:
                headers, _, body = req.partition(b"\r\n\r\n")
                lines = headers.split(b"\r\n")
                new_headers = [lines[0], f"Proxy-Authorization: Basic {auth_str}".encode("utf-8")] + lines[1:]
                forward_req = b"\r\n".join(new_headers) + b"\r\n\r\n" + body
                remote_ssock.sendall(forward_req)

            sockets = [client_sock, remote_ssock]
            while self.is_running:
                r, _, _ = select.select(sockets, [], [], 20)
                if not r:
                    break
                closed = False
                for s in r:
                    other = remote_ssock if s is client_sock else client_sock
                    try:
                        data = s.recv(32768)
                        if not data:
                            closed = True
                            break
                        self.total_bytes += len(data)
                        other.sendall(data)
                    except Exception:
                        closed = True
                        break
                if closed:
                    break

        except Exception:
            pass
        finally:
            try:
                client_sock.close()
            except Exception:
                pass
            if remote_ssock:
                try:
                    remote_ssock.close()
                except Exception:
                    pass


# =========================================================================
# 4. Latency / Deep Ping Handshake (Preserved)
# =========================================================================
def test_single_server(host, port, username, password, target_url="https://web.telegram.org/", timeout=6):
    try:
        start = time.perf_counter()
        ctx = ssl.create_default_context()
        sock = socket.create_connection((host, port), timeout=timeout)
        ssock = ctx.wrap_socket(sock, server_hostname=host)

        parsed = urlparse(target_url)
        target_host = parsed.hostname or "web.telegram.org"
        target_port = parsed.port or (443 if parsed.scheme == "https" else 80)

        auth_str = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("utf-8")
        connect_cmd = (
            f"CONNECT {target_host}:{target_port} HTTP/1.1\r\n"
            f"Host: {target_host}:{target_port}\r\n"
            f"Proxy-Authorization: Basic {auth_str}\r\n"
            f"User-Agent: Mozilla/5.0\r\n"
            f"Proxy-Connection: Keep-Alive\r\n\r\n"
        )
        ssock.sendall(connect_cmd.encode("utf-8"))

        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = ssock.recv(4096)
            if not chunk:
                break
            resp += chunk

        latency_ms = int((time.perf_counter() - start) * 1000)
        ssock.close()

        if b"200" in resp:
            return True, latency_ms, "OK"
        else:
            return False, latency_ms, resp.split(b"\r\n")[0].decode("utf-8", errors="ignore")
    except Exception as e:
        return False, 0, str(e)


# =========================================================================
# 5. Bridge JavaScript API for Main Window
# =========================================================================
class MeisamVpnJsApi:
    def __init__(self, app):
        self.app = app

    def get_initial_state(self):
        return {
            "status": self.app.connection_status,
            "server_code": self.app.selected_region_code,
            "server_name": self.app.selected_server_name,
            "theme": self.app.theme,
            "widget_enabled": self.app.widget_enabled,
            "version": APP_VERSION,
            "data_mb": round(self.app.get_total_mb(), 1),
            "data_formatted": self.app.get_formatted_traffic(),
            "data_percent": self.app.get_traffic_percent()
        }

    def toggle_connect(self):
        threading.Thread(target=self.app.toggle_connect, daemon=True).start()

    def select_server(self, code, country="", city=""):
        self.app.select_server(code, country, city)

    def measure_ping(self, code):
        return self.app.measure_ping(code)

    def toggle_widget(self, enabled):
        self.app.set_widget(enabled)

    def set_theme(self, theme):
        self.app.set_theme(theme)


# =========================================================================
# 6. Bridge JavaScript API for Floating Desktop Widget
# =========================================================================
class MeisamVpnWidgetApi:
    def __init__(self, app):
        self.app = app

    def widget_click(self):
        threading.Thread(target=self.app.toggle_connect, daemon=True).start()

    def widget_dblclick(self):
        self.app.focus_main_window()


# =========================================================================
# 7. Main Application Controller
# =========================================================================
class MeisamVpnApp:
    def __init__(self):
        self.api = VeePNApi()
        self.tunnel = None
        self.connection_status = "disconnected" # "disconnected", "connecting", "connected"
        self.selected_region_code = "gb-lnd"
        self.selected_server_name = "United Kingdom"
        self.widget_enabled = False
        self.theme = "dark"
        self.window = None
        self.widget_window = None
        self.baseline_bytes = 0 # Fresh install starts at 0 bytes

        self.load_settings()
        atexit.register(self.cleanup)

    def load_settings(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.theme = data.get("theme", "dark")
                    self.widget_enabled = data.get("widget_enabled", False)
                    self.selected_region_code = data.get("selected_region", "gb-lnd")
                    for code, c_name, _ in FREE_REGIONS:
                        if code == self.selected_region_code:
                            self.selected_server_name = c_name
                            break
            except Exception:
                pass

    def save_settings(self):
        try:
            existing = {}
            if os.path.exists(CONFIG_FILE):
                try:
                    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                        existing = json.load(f)
                except Exception:
                    pass
            existing.update({
                "theme": self.theme,
                "widget_enabled": self.widget_enabled,
                "selected_region": self.selected_region_code
            })
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(existing, f, indent=2)
        except Exception:
            pass

    def get_total_bytes(self):
        session_bytes = self.tunnel.total_bytes if self.tunnel else 0
        return self.baseline_bytes + session_bytes

    def get_total_mb(self):
        return self.get_total_bytes() / (1024 * 1024)

    def get_formatted_traffic(self):
        mb = self.get_total_mb()
        if mb >= 1000:
            return f"{mb / 1000:.2f} GB"
        if mb < 0.1:
            return "0 MB"
        return f"{mb:.1f} MB"

    def get_traffic_percent(self):
        mb = self.get_total_mb()
        pct = (mb / 1000.0) * 100.0
        return min(100.0, pct)

    def set_theme(self, theme):
        self.theme = theme
        self.save_settings()

    def set_widget(self, enabled):
        self.widget_enabled = bool(enabled)
        self.save_settings()
        if self.widget_window:
            try:
                if self.widget_enabled:
                    self.widget_window.show()
                    self.widget_window.evaluate_js(f"if(window.setState) window.setState('{self.connection_status}');")
                else:
                    self.widget_window.hide()
            except Exception as e:
                print("Widget toggle error:", e)

    def focus_main_window(self):
        if self.window:
            try:
                self.window.restore()
                self.window.show()
            except Exception:
                pass

    def select_server(self, code, country="", city=""):
        norm_code = REGION_CODE_MAP.get(code.lower(), "gb-lnd")
        self.selected_region_code = norm_code
        if country:
            self.selected_server_name = country
        self.save_settings()

    def measure_ping(self, code):
        norm_code = REGION_CODE_MAP.get(code.lower(), "gb-lnd")
        try:
            srvs = self.api.get_servers_for_region(norm_code)
            if srvs:
                s = srvs[0]
                u, p = s["username"], s["password"]
                h = s["addresses"][0]
                port = s["port"]
                ok, latency, msg = test_single_server(h, port, u, p, target_url=DEEP_TEST_URL, timeout=6)
                if ok:
                    return {"ok": True, "ping": latency}
            return {"ok": False, "ping": None}
        except Exception:
            return {"ok": False, "ping": None}

    def set_state_ui(self, state, server_name=""):
        self.connection_status = state
        if self.widget_window:
            try:
                self.widget_window.evaluate_js(f"if(window.setState) window.setState('{state}');")
            except Exception:
                pass
        if self.window:
            try:
                self.window.evaluate_js(f"if(window.setVpnState) window.setVpnState('{state}', '{server_name}');")
            except Exception:
                pass

    def toggle_connect(self):
        if self.connection_status == "connected":
            self.disconnect()
        elif self.connection_status == "disconnected":
            self.connect()

    def connect(self):
        self.set_state_ui("connecting")
        try:
            norm_code = REGION_CODE_MAP.get(self.selected_region_code.lower(), "gb-lnd")
            servers = self.api.get_servers_for_region(norm_code)
            if not servers:
                raise Exception("هیچ سروری برای این لوکیشن یافت نشد")
            srv = servers[0]

            host = srv["addresses"][0]
            port = srv["port"]
            u, p = srv["username"], srv["password"]

            if self.tunnel:
                self.tunnel.stop()

            self.tunnel = LocalTunnelServer(host, port, u, p, local_port=LOCAL_PORT)
            self.tunnel.start()

            WindowsProxyManager.set_proxy(f"127.0.0.1:{LOCAL_PORT}")
            self.set_state_ui("connected", self.selected_server_name)

        except Exception as e:
            print("Connect error:", e)
            WindowsProxyManager.disable_proxy()
            if self.tunnel:
                self.tunnel.stop()
                self.tunnel = None
            self.set_state_ui("disconnected")

    def disconnect(self):
        self.set_state_ui("disconnected")
        WindowsProxyManager.disable_proxy()
        if self.tunnel:
            self.tunnel.stop()
            self.tunnel = None

    def start_traffic_monitor(self):
        def loop():
            while True:
                time.sleep(2)
                if self.window:
                    try:
                        mb = self.get_total_mb()
                        fmt = self.get_formatted_traffic()
                        pct = self.get_traffic_percent()
                        self.window.evaluate_js(f"if(window.updateTraffic) window.updateTraffic({mb:.1f}, '{fmt}', {pct:.1f});")
                    except Exception:
                        pass
        t = threading.Thread(target=loop, daemon=True)
        t.start()

    def cleanup(self):
        WindowsProxyManager.disable_proxy()
        if self.tunnel:
            self.tunnel.stop()
        if self.widget_window:
            try:
                self.widget_window.destroy()
            except Exception:
                pass

    def run(self):
        base_dir = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
        ui_path = os.path.join(base_dir, "app_ui", "index.html")
        if not os.path.exists(ui_path):
            ui_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_ui", "index.html")

        widget_path = os.path.join(base_dir, "app_ui", "widget.html")
        if not os.path.exists(widget_path):
            widget_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_ui", "widget.html")

        # 1. Main Window
        self.window = webview.create_window(
            title=APP_NAME,
            url=ui_path,
            js_api=MeisamVpnJsApi(self),
            width=450,
            height=860,
            resizable=False,
            easy_drag=True
        )

        # 2. Native Transparent Floating Desktop Widget Window
        self.widget_window = webview.create_window(
            title="Meisam VPN Widget",
            url=widget_path,
            js_api=MeisamVpnWidgetApi(self),
            width=72,
            height=72,
            frameless=True,
            on_top=True,
            transparent=True,
            easy_drag=True,
            x=140,
            y=140,
            hidden=not self.widget_enabled
        )

        self.start_traffic_monitor()
        webview.start(debug=False)


if __name__ == "__main__":
    app = MeisamVpnApp()
    app.run()
