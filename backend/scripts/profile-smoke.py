#!/usr/bin/env python3
"""Login and read own profile without printing credentials, tokens, or response bodies."""
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    base = os.environ.get("SIP_TEST_BASE_URL", "").rstrip("/")
    url = urllib.parse.urlsplit(base)
    local = base in ("http://localhost:8080", "http://127.0.0.1:8080")
    if (not local and url.scheme != "https") or not url.hostname or any(
        (url.username, url.password, url.path, url.query, url.fragment)
    ):
        raise ValueError("Invalid server origin")
    opener = urllib.request.build_opener(NoRedirect)

    def request(path, payload=None, token=None):
        headers = {"Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, data=data, headers=headers)
        try:
            response = opener.open(req, timeout=35)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("Response too large")
            body = json.loads(raw)
            if not isinstance(body, dict):
                raise ValueError("Invalid response")
            return response.code, response.headers.get("Cache-Control"), body

    status, cache, login = request("/api/auth/login", {
        "email": input("Email: "), "password": getpass.getpass("Password: ")
    })
    print("Login HTTP", status)
    if status != 200 or cache != "no-store":
        return 1
    token = login["session"]["accessToken"]
    expected_id = login["user"]["id"]
    del login
    if not isinstance(token, str) or not token:
        return 1
    status, cache, profile = request("/api/users/me", token=token)
    del token
    fields = {"id", "email", "role", "firstName", "lastName", "bio",
              "avatarUrl", "organizationId", "createdAt"}
    ok = (status == 200 and cache == "no-store" and set(profile) == fields
          and profile.get("id") == expected_id)
    print("Profile HTTP", status, "own_profile_and_contract_valid=", ok)
    for token in (None, "invalid-smoke-token"):
        status, cache, body = request("/api/users/me", token=token)
        passed = status == 401 and cache == "no-store" and body.get("code") == "UNAUTHORIZED"
        print("Missing token" if token is None else "Invalid token", "HTTP", status, "passed=", passed)
        ok = ok and passed
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Exception, KeyboardInterrupt):
        # Never print exceptions: request objects or provider responses may contain secrets.
        print("Smoke test failed; response bodies and credentials withheld.", file=sys.stderr)
        sys.exit(1)
