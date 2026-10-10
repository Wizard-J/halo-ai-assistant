#!/usr/bin/env python3
"""Official SIWC loopback OAuth helper. Run on the computer with your browser, never on Halo."""
import argparse
import base64
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser

ISSUER = "https://auth.openai.com"
RESOURCE = "https://api.openai.com/v1"
PLAN_SCOPE = "chatgpt.tokens.use.direct"
SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE


def write_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.parent.is_symlink() or path.is_symlink():
        raise ValueError("授权目录或文件不能是符号链接")
    os.chmod(path.parent, 0o700)
    fd, temporary = tempfile.mkstemp(prefix=".oauth-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, ensure_ascii=False)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_private(path):
    if not path.exists():
        return {}
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("授权文件权限必须为 0600，且不能是符号链接")
    return json.loads(path.read_text())


def fetch_json(url, form=None):
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    request = urllib.request.Request(url, data=data)
    if data is not None:
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise ValueError(f"OpenAI 授权请求失败（HTTP {error.code}），请重试授权") from None
    except Exception:
        raise ValueError("无法连接 OpenAI 授权服务，请检查网络") from None


def validate_callback(query, state, client_id):
    values = urllib.parse.parse_qs(query, strict_parsing=True)
    if len(values.get("state", [])) != 1 or not secrets.compare_digest(values["state"][0], state):
        raise ValueError("授权回调 state 不匹配")
    if "error" in values:
        raise ValueError("授权未完成或已拒绝，请重新登录并允许订阅使用")
    if len(values.get("code", [])) != 1:
        raise ValueError("授权回调缺少有效 code")
    ids = values.get("client_id", [])
    if client_id == "dynamic_agent_client":
        if len(ids) != 1 or not ids[0] or ids[0] == "dynamic_agent_client":
            raise ValueError("授权回调缺少签发的 client_id")
        issued = ids[0]
    else:
        if ids and (len(ids) != 1 or ids[0] != client_id):
            raise ValueError("授权回调 client_id 不匹配")
        issued = client_id
    return values["code"][0], issued


def validate_identity(token, client_id, nonce, discovery, old_subject=None):
    import jwt
    jwks_uri = discovery["jwks_uri"]
    if urllib.parse.urlparse(jwks_uri).scheme != "https" or urllib.parse.urlparse(jwks_uri).hostname != "auth.openai.com":
        raise ValueError("无效的 OpenAI JWKS 地址")
    key = jwt.PyJWKClient(jwks_uri).get_signing_key_from_jwt(token).key
    claims = jwt.decode(token, key, algorithms=["RS256", "ES256"], issuer=ISSUER,
                        audience=client_id, options={"require": ["iss", "aud", "exp", "sub", "nonce"]})
    if not secrets.compare_digest(claims["nonce"], nonce):
        raise ValueError("ID token nonce 不匹配")
    if old_subject and claims["sub"] != old_subject:
        raise ValueError("返回的账户身份发生变化，请使用单独的授权目录连接新账户")
    return claims


def login(directory):
    profile_path = directory / "credentials.json"
    profile = read_private(profile_path)
    registration = read_private(directory / "registration.json")
    host = read_private(directory / "host.json")
    if not host:
        host = {"ext_agent_host_id": "urn:uuid:" + str(uuid.uuid4())}
        write_private(directory / "host.json", host)
    client_id = profile.get("client_id") or registration.get("client_id") or "dynamic_agent_client"
    state, nonce, verifier = (secrets.token_urlsafe(32) for _ in range(3))
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    outcome = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # HTTP logs would expose authorization codes and ID-token hints.

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/auth/callback":
                self.send_error(404)
                return
            try:
                outcome["result"] = validate_callback(parsed.query, state, client_id)
                message = "Authorization received. You can return to the terminal."
                code = 200
            except ValueError as error:
                # Invalid state cannot abort someone else's pending login.
                code, message = 400, str(error)
                if "state" not in message:
                    outcome["error"] = message
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(message.encode())

    discovery = fetch_json(ISSUER + "/.well-known/openid-configuration")
    if discovery.get("issuer") != ISSUER:
        raise ValueError("OpenAI issuer 不匹配")
    with http.server.HTTPServer(("127.0.0.1", 0), Callback) as server:
        redirect = f"http://127.0.0.1:{server.server_port}/auth/callback"
        params = dict(client_id=client_id, ext_agent_host_id=host["ext_agent_host_id"], response_type="code",
                      redirect_uri=redirect, scope=SCOPES, resource=RESOURCE, state=state, nonce=nonce,
                      code_challenge_method="S256", code_challenge=challenge)
        if client_id == "dynamic_agent_client":
            params["agent_name_hint"] = "Halo AI Assistant"
        elif profile.get("id_token"):
            params["id_token_hint"] = profile["id_token"]
        print("正在打开浏览器：Continue with ChatGPT。请登录并允许应用使用订阅额度。")
        if not webbrowser.open(ISSUER + "/api/accounts/authorize?" + urllib.parse.urlencode(params)):
            raise ValueError("无法打开浏览器，请在带桌面浏览器的本机终端运行此助手")
        server.timeout = 1
        deadline = time.monotonic() + 300
        while not outcome and time.monotonic() < deadline:
            server.handle_request()
    if "result" not in outcome:
        raise ValueError(outcome.get("error", "授权等待超时，请重新运行"))
    code, issued = outcome["result"]
    write_private(directory / "registration.json", {"client_id": issued})
    tokens = fetch_json(ISSUER + "/api/accounts/oauth/token", dict(
        grant_type="authorization_code", client_id=issued, code=code, code_verifier=verifier,
        redirect_uri=redirect, resource=RESOURCE))
    claims = validate_identity(tokens.get("id_token", ""), issued, nonce, discovery, profile.get("subject"))
    scopes = tokens.get("scope", "").split()
    if PLAN_SCOPE not in scopes:
        raise ValueError("未授予 ChatGPT 订阅使用权限；未保存授权凭据")
    if not tokens.get("access_token") or not tokens.get("refresh_token") or tokens.get("expires_in", 0) <= 0:
        raise ValueError("授权响应不完整；未保存授权凭据")
    if tokens.get("token_type", "").lower() != "bearer":
        raise ValueError("授权 token_type 无效")
    record = {key: tokens[key] for key in ["access_token", "refresh_token", "id_token", "token_type", "expires_in"]}
    record.update(client_id=issued, ext_agent_host_id=host["ext_agent_host_id"], issuer=ISSUER,
                  subject=claims["sub"], email=claims.get("email", "ChatGPT"), scopes=scopes,
                  saved_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    write_private(profile_path, record)
    print(f"授权已保存：{profile_path}\n凭据请通过安全通道导入 Halo 的独立授权目录；不要上传到聊天、Git 或插件设置。")


def main():
    parser = argparse.ArgumentParser(description="Halo AI Assistant: Continue with ChatGPT（本机授权）")
    parser.add_argument("--directory", type=Path, default=Path.home() / ".config/halo-ai-assistant/chatgpt-local",
                        help="本机独立授权目录；不同账户使用不同目录")
    args = parser.parse_args()
    try:
        import jwt  # Fail before opening browser if the verifier isn't installed.
        login(args.directory.expanduser().absolute())
    except KeyboardInterrupt:
        print("授权已取消。", file=sys.stderr)
        return 1
    except Exception as error:
        # Library exceptions may contain token contents: only our explicit validation messages are safe.
        print(str(error) if type(error) is ValueError else "授权失败；请检查依赖、网络并重新授权。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
