#!/usr/bin/env python3
"""Create a least-privilege, read-only role and user for VM Tune-Up in
HPE Morpheus, then print an API token for that user.

Usage:  python3 create-readonly-user.py <morpheus-ip-or-hostname>

Everything else is prompted for. The script uses only the Python standard
library, writes no files, keeps no logs, and never echoes passwords. When it
finishes it deletes the admin token it created for its own login, so the only
thing left behind is the new role, the new user and that user's token.

What the role gets, and nothing else (0.4.0; modeled on create-com-user.py in
com-morpheus-plugin, same author, MIT):
- "read" on the pages VM Tune-Up's tabs live on and the data it reads:
    Operations: Dashboard, Infrastructure: Compute, Clusters, Groups, Clouds
- read access to all groups and clouds (default access), so those pages list
  the VMs, hosts and clusters.
- the plugin's own permissions:
    VM Tune-Up: View results    = Read
    VM Tune-Up: Run guest scans = None  (Full only with --guest-scan; it
      lets the user run the fixed no-op guest agent check in VMs)
Every other permission stays at "none". The script reads the role back, prints
what was granted, and stops before creating a user if anything else is above none.
The plugin's permissions exist only while the plugin is installed: install it first.
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import http.client
import json
import socket
import ssl
import sys
import urllib.parse
from datetime import datetime, timezone

CLIENT_ID = "morph-api"

VIEW_PERMISSION = "vm-tuneup-view"
GUEST_SCAN_PERMISSION = "vm-tuneup-guest-scan"

# The only feature permissions granted (read). Codes as in com-morpheus-plugin's
# create-com-user.py, checked there on Morpheus 9.0.1 and 9.1.0.
ALLOW_READ = {
    "dashboard": "Operations: Dashboard",
    "admin-servers": "Infrastructure: Compute",
    "infrastructure-cluster": "Infrastructure: Clusters",
    "admin-groups": "Infrastructure: Groups",
    "admin-zones": "Infrastructure: Clouds",
}

# Default (global) access, set to "read": groups (ComputeSite) and clouds (ComputeZone).
GLOBAL_ACCESS = ("ComputeSite", "ComputeZone")

READ_LEVELS = ("read", "view")
NONE_LEVELS = {"none", "no", None}
GRANTED_LEVELS = {"read", "view"}


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status, self.body = status, body


# -- TLS: system trust, or trust-on-first-use with the fingerprint shown ------
class Server:
    def __init__(self, host: str, port: int = 443):
        self.host, self.port = host, port
        self.pinned: str | None = None
        self.ctx = ssl.create_default_context()
        try:
            with socket.create_connection((host, port), timeout=15) as s, \
                    self.ctx.wrap_socket(s, server_hostname=host):
                pass
            print(f"TLS certificate for {host} is trusted by this system.")
        except ssl.SSLCertVerificationError:
            pem = ssl.get_server_certificate((host, port), timeout=15)
            der = ssl.PEM_cert_to_DER_cert(pem)
            fp = hashlib.sha256(der).hexdigest().upper()
            pretty = ":".join(fp[i:i + 2] for i in range(0, len(fp), 2))
            print(f"\n{host} uses a certificate this system does not trust "
                  f"(usually self-signed).\nSHA-256 fingerprint:\n  {pretty}")
            print("Compare it with the appliance's certificate before continuing.")
            if not confirm("Trust this certificate for this run only?"):
                sys.exit("Stopped: certificate not trusted.")
            self.pinned = fp
            self.ctx = ssl.create_default_context()
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE  # replaced by the fingerprint check below

    def request(self, method: str, path: str, token: str | None = None,
                body: dict | None = None, form: dict | None = None) -> dict:
        conn = http.client.HTTPSConnection(self.host, self.port, context=self.ctx, timeout=60)
        try:
            conn.connect()
            if self.pinned:
                der = conn.sock.getpeercert(binary_form=True)
                if hashlib.sha256(der).hexdigest().upper() != self.pinned:
                    raise SystemExit("Stopped: the server certificate changed during the run.")
            headers = {"Accept": "application/json"}
            data = None
            if token:
                headers["Authorization"] = f"Bearer {token}"
            if form is not None:
                data = urllib.parse.urlencode(form).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            elif body is not None:
                data = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
            conn.request(method, path, body=data, headers=headers)
            resp = conn.getresponse()
            text = resp.read().decode("utf-8", "replace")
            if resp.status >= 400:
                raise ApiError(resp.status, text)
            return json.loads(text) if text.strip() else {}
        finally:
            conn.close()

    def login(self, username: str, password: str) -> dict:
        return self.request("POST", "/oauth/token", form={
            "grant_type": "password", "scope": "write", "client_id": CLIENT_ID,
            "username": username, "password": password})

    def delete_own_token(self, token: str) -> bool:
        """Delete exactly this token (not the user's other tokens)."""
        try:
            found = self.request("POST", "/api/tokens/lookup", token=token, body={"token": token})
            tid = (found.get("token") or {}).get("id")
            if tid:
                self.request("DELETE", f"/api/tokens/{tid}", token=token)
                return True
        except (ApiError, OSError):
            pass
        return False


# -- prompts -------------------------------------------------------------------
def confirm(question: str, default: bool = False) -> bool:
    hint = "[Y/n]" if default else "[y/N]"
    answer = input(f"{question} {hint} ").strip().lower()
    return default if not answer else answer in ("y", "yes")


def ask(question: str, default: str | None = None) -> str:
    while True:
        suffix = f" [{default}]" if default else ""
        answer = input(f"{question}{suffix}: ").strip()
        if answer or default:
            return answer or default
        print("  A value is required.")


def ask_password(question: str) -> str:
    while True:
        first = getpass.getpass(f"{question}: ")
        if not first:
            print("  A password is required.")
            continue
        if getpass.getpass("Repeat the password: ") == first:
            return first
        print("  The passwords do not match. Try again.")


def message(err: ApiError) -> str:
    try:
        data = json.loads(err.body)
        parts = [data.get("msg") or data.get("error_description") or data.get("error") or ""]
        errors = data.get("errors") or {}
        parts += [f"{k}: {v}" for k, v in errors.items()] if isinstance(errors, dict) else []
        return "; ".join(p for p in parts if p) or err.body[:200]
    except ValueError:
        return err.body[:200]


# -- steps ---------------------------------------------------------------------
def create_role(srv: Server, admin: str) -> tuple[int, str]:
    print("\nA new role with the read-only permissions VM Tune-Up needs will be created for this user.")
    while True:
        name = ask("Name for the read-only role", "vm-tuneup-viewer")
        try:
            res = srv.request("POST", "/api/roles", token=admin, body={"role": {
                "authority": name, "roleType": "user",
                "description": "VM Tune-Up, read-only (created by create-readonly-user.py)"}})
            return res["role"]["id"], name
        except ApiError as e:
            print(f"  Could not create role '{name}': {message(e)}")
            if not confirm("Try a different name?", True):
                sys.exit("Stopped: no role created.")


def grant(srv: Server, admin: str, role_id: int, guest_scan: bool) -> dict:
    role = srv.request("GET", f"/api/roles/{role_id}?includeDefaultAccess=true", token=admin)
    path = f"/api/roles/{role_id}/update-permission"
    present = {p.get("code") for p in role.get("featurePermissions", [])}
    if VIEW_PERMISSION not in present:
        sys.exit("Stopped: the role has no VM Tune-Up permissions. Install the plugin first, "
                 f"then delete the empty role (id {role_id}) in Administration > Roles and run again.")
    counts = {"read": [], "missing": [], "global": []}
    for code, label in ALLOW_READ.items():
        if code not in present:
            counts["missing"].append(label)
            continue
        for level in READ_LEVELS:
            try:
                srv.request("PUT", path, token=admin, body={"permissionCode": code, "access": level})
                counts["read"].append(f"{label} = {level}")
                break
            except ApiError as e:
                if e.status not in (400, 422):
                    raise
        else:
            counts["missing"].append(label)
    for code in GLOBAL_ACCESS:
        try:
            srv.request("PUT", path, token=admin, body={"permissionCode": code, "access": "read"})
            counts["global"].append(code)
        except ApiError as e:
            if e.status not in (400, 422):
                raise
    srv.request("PUT", path, token=admin, body={"permissionCode": VIEW_PERMISSION, "access": "read"})
    counts["read"].append("VM Tune-Up: View results = read")
    if GUEST_SCAN_PERMISSION in present:
        srv.request("PUT", path, token=admin, body={
            "permissionCode": GUEST_SCAN_PERMISSION, "access": "full" if guest_scan else "none"})
        counts["read"].append("VM Tune-Up: Run guest scans = " + ("full" if guest_scan else "none"))
    counts["problems"] = verify_role(srv, admin, role_id, guest_scan)
    return counts


def verify_role(srv: Server, admin: str, role_id: int, guest_scan: bool) -> list[str]:
    """Read the role back. Only the allowlisted permissions may be read, the view
    permission must be read, the guest-scan permission full only when asked for,
    and everything else none. Morpheus decides which levels a permission accepts,
    so check what it actually stored."""
    role = srv.request("GET", f"/api/roles/{role_id}?includeDefaultAccess=true", token=admin)
    bad = []
    levels = {p.get("code"): p.get("access") for p in role.get("featurePermissions", [])}
    for code, access in levels.items():
        if code == GUEST_SCAN_PERMISSION:
            want = "full" if guest_scan else "none"
            if access != want and not (want == "none" and access in NONE_LEVELS):
                bad.append(f"{code}={access} (expected {want})")
        elif code == VIEW_PERMISSION:
            if access != "read":
                bad.append(f"{code}={access} (expected read)")
        elif code in ALLOW_READ:
            if access not in GRANTED_LEVELS | NONE_LEVELS:
                bad.append(f"{code}={access} (expected read)")
        elif access not in NONE_LEVELS:
            bad.append(f"{code}={access} (expected none)")
    for key, value in role.items():
        if key.startswith("global") and key.endswith("Access") and value not in GRANTED_LEVELS | NONE_LEVELS | {"custom"}:
            bad.append(f"{key}={value}")
    return bad


def create_user(srv: Server, admin: str, role_id: int) -> tuple[str, str]:
    print("\nNow the read-only user.")
    while True:
        username = ask("Username for the read-only user")
        email = ask("Email for the user (required by Morpheus)", f"{username}@example.invalid")
        password = ask_password(f"Password for {username}")
        try:
            srv.request("POST", "/api/users", token=admin, body={"user": {
                "username": username, "email": email, "firstName": "Read-only",
                "lastName": username, "password": password,
                "passwordConfirmation": password, "roles": [{"id": role_id}],
                "receiveNotifications": False}})
            return username, password
        except ApiError as e:
            print(f"  Could not create user: {message(e)}")
            if not confirm("Try again?", True):
                sys.exit("Stopped: role created but no user (delete the role in "
                         "Administration > Roles if it is not wanted).")


def make_token(srv: Server, username: str, password: str, label: str) -> dict:
    session = srv.login(username, password)
    login_token = session["access_token"]
    info = {"token": login_token, "expires_in": session.get("expires_in"), "kind": "login token"}
    try:  # prefer a named API key so it shows up under User Settings > API Keys
        res = srv.request("POST", "/api/tokens", token=login_token,
                          body={"token": {"clientId": CLIENT_ID, "name": label}})
        tok = res.get("token") or {}
        value = tok.get("accessToken") or tok.get("access_token")
        if value and "*" not in value:
            srv.delete_own_token(login_token)
            info = {"token": value, "expiration": tok.get("expiration"), "kind": "named API key"}
    except ApiError:
        pass
    return info


def verify(srv: Server, token: str) -> list[str]:
    notes = []
    who = srv.request("GET", "/api/whoami", token=token)
    roles = [r.get("authority") for r in (who.get("user") or {}).get("roles", [])]
    notes.append(f"whoami: {who.get('user', {}).get('username')} roles={roles}")
    try:
        srv.request("POST", "/api/mcp", token=token, body={
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "create-readonly-user", "version": "1"}}})
        notes.append("MCP endpoint /api/mcp: reachable with this token")
    except ApiError as e:
        notes.append(f"MCP endpoint /api/mcp: not available to this user ({e.status})")
    return notes


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("server", help="Morpheus appliance IP address or hostname")
    p.add_argument("--port", type=int, default=443)
    p.add_argument("--guest-scan", action="store_true",
                   help='also grant "VM Tune-Up: Run guest scans" at full (runs the no-op guest agent check)')
    args = p.parse_args()

    srv = Server(args.server, args.port)
    print("\nLog in with an administrator account (used only for this run).")
    admin_user = ask("Admin username")
    try:
        admin = srv.login(admin_user, getpass.getpass("Admin password: "))["access_token"]
    except ApiError as e:
        sys.exit(f"Admin login failed: {message(e)}")

    try:
        role_id, role_name = create_role(srv, admin)
        print(f"Created role '{role_name}' (id {role_id}). Setting read-only permissions ...")
        counts = grant(srv, admin, role_id, args.guest_scan)
        print("  Granted:")
        for item in counts["read"]:
            print(f"    {item}")
        print(f"    default access (read): {', '.join(counts['global']) or 'none'}")
        if counts["missing"]:
            print(f"  Not on this appliance (left out): {', '.join(counts['missing'])}")
        if counts["problems"]:
            print("\nThe role has more access than intended, so no user was created:")
            for item in counts["problems"]:
                print(f"  - {item}")
            sys.exit(f"Review role '{role_name}' in Administration > Roles, then run again.")
        print("  Checked: every other permission on the role is none.")
        username, password = create_user(srv, admin, role_id)
        print(f"Created user '{username}'.")
    finally:
        removed = srv.delete_own_token(admin)
        print("Removed this run's admin token." if removed else
              "Note: could not remove this run's admin token; it expires on its own.")

    label = f"{role_name} {datetime.now(timezone.utc):%Y-%m-%d}"
    tok = make_token(srv, username, password, label)
    del password
    for note in verify(srv, tok["token"]):
        print("  " + note)

    when = tok.get("expiration") or (
        f"in {int(tok['expires_in']) // 86400} days" if tok.get("expires_in") else "unknown")
    print("\n" + "=" * 64)
    print(f"Read-only user : {username}  (role: {role_name})")
    print(f"Token ({tok['kind']}), expires {when}:")
    print(f"  {tok['token']}")
    print("=" * 64)
    print("Store the token now (for example in a chmod 600 env file). This script did not\n"
          "save it anywhere, and Morpheus shows it only masked from now on. It is still in\n"
          "this terminal's scrollback: clear it (for example with `clear` and a new window)\n"
          "when you're done.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\nCancelled.")
