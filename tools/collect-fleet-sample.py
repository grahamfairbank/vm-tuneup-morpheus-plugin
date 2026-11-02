#!/usr/bin/env python3
"""Collect an anonymized, read-only sample of VM data from an HPE Morpheus appliance.

VM Tune-Up uses this sample to check its rules against real fleets. The script:

- only sends GET requests, using a token you paste in (a read-only user is enough);
- saves only the fields the checks need, from an allowlist;
- replaces every name, host name, IP address, MAC address (except its first three
  bytes, the vendor prefix) and UUID with a keyed hash. The key is random for each run
  and never saved, so the tokens can't be reversed, but the same value always gets
  the same token within one file, so matches (for example a VMware VM and an HVM VM with
  the same MAC address) still show up;
- replaces record ids (VM, cloud, cluster and migration ids) with keyed tokens too, so a
  reader with access to the appliance can't look rows up by id;
- keeps creation dates to the month (dateCreated) and other timestamps to the day;
- writes one JSON file, readable by you only (mode 0600; an existing file is never
  overwritten), and prints a summary. Review the file before you share it;
- on an error from the appliance, prints only the HTTP status, never the answer body.

Format "vm-tuneup-fleet-sample/2" (0.4.0): ids are tokens ("srv-...", "cloud-...",
"cluster-...", "mig-...") instead of numbers, dateCreated is "YYYY-MM", lastAgentUpdate and
the oldest snapshot date are "YYYY-MM-DD". Format /1 kept numeric ids and full timestamps.

Usage:
    python collect-fleet-sample.py <appliance-host-name> [--out FILE] [--max-vms N]

Needs Python 3.8 or later. Standard library only.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import getpass
import hashlib
import hmac
import http.client
import json
import os
import secrets
import socket
import ssl
import sys
import time
import urllib.parse

VMWARE_OUIS = {"00:50:56", "00:0c:29", "00:05:69", "00:1c:14"}


# -- TLS: system trust, or trust-on-first-use with the fingerprint shown ------
class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}")
        self.status, self.body = status, body


class Server:
    def __init__(self, host: str, port: int = 443):
        self.host, self.port = host, port
        self.pinned = None
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
            print("Compare it with the fingerprint shown for the certificate in the appliance UI (or by its administrator) before continuing.")
            if input("Trust this certificate for this run only? [y/N] ").strip().lower() != "y":
                sys.exit("Stopped: certificate not trusted.")
            self.pinned = fp
            self.ctx = ssl.create_default_context()
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE  # replaced by the fingerprint check below
        self.token = None
        self.timings = collections.defaultdict(list)

    def get(self, path: str, params: dict | None = None, kind: str = "other"):
        if params:
            path = f"{path}?{urllib.parse.urlencode(params)}"
        conn = http.client.HTTPSConnection(self.host, self.port, context=self.ctx, timeout=180)
        start = time.monotonic()
        try:
            conn.connect()
            if self.pinned:
                der = conn.sock.getpeercert(binary_form=True)
                if hashlib.sha256(der).hexdigest().upper() != self.pinned:
                    raise SystemExit("Stopped: the server certificate changed during the run.")
            conn.request("GET", path, headers={"Accept": "application/json",
                                               "Authorization": f"Bearer {self.token}"})
            resp = conn.getresponse()
            text = resp.read().decode("utf-8", "replace")
            if resp.status >= 400:
                raise ApiError(resp.status, text)
            return json.loads(text) if text.strip() else {}
        finally:
            self.timings[kind].append(time.monotonic() - start)
            conn.close()

    def get_all(self, path: str, key: str, params: dict | None = None, kind: str = "list",
                page: int = 100, limit: int | None = None) -> list:
        out, offset = [], 0
        while True:
            p = dict(params or {}, max=page, offset=offset)
            data = self.get(path, p, kind)
            items = data.get(key) or []
            out.extend(items)
            total = (data.get("meta") or {}).get("total", len(out))
            offset += len(items)
            if not items or offset >= total or (limit and len(out) >= limit):
                return out[:limit] if limit else out


# -- anonymization ----------------------------------------------------------
class Anon:
    def __init__(self):
        self.key = secrets.token_bytes(32)

    def _h(self, value, n=10) -> str:
        return hmac.new(self.key, str(value).strip().lower().encode(), hashlib.sha256).hexdigest()[:n]

    def tok(self, prefix: str, value):
        if value in (None, ""):
            return None
        return f"{prefix}-{self._h(value)}"

    def id(self, kind: str, value):
        """A record id as a keyed token; the kind is part of the key input, so VM 5 and cloud 5 differ."""
        if value in (None, ""):
            return None
        return f"{kind}-{self._h(f'{kind}:{value}')}"

    def mac(self, value):
        if not value:
            return None
        m = str(value).strip().lower().replace("-", ":")
        oui = m[:8]
        h = self._h(m, 6)
        return f"{oui}:{h[0:2]}:{h[2:4]}:{h[4:6]}"


def month(value):
    """'2024-05-17T10:00:00Z' -> '2024-05'; None stays None."""
    return str(value)[:7] if value else None


def day(value):
    """'2024-05-17T10:00:00Z' -> '2024-05-17'; None stays None."""
    return str(value)[:10] if value else None


def g(d, *path, default=None):
    for p in path:
        if not isinstance(d, dict):
            return default
        d = d.get(p)
    return default if d is None else d


# -- per-VM record (allowlist) ------------------------------------------------
CONFIG_KEYS = ["machineType", "disableQgaChannel", "attachVirtIODrivers", "numaMode",
               "nestedVirtualization", "hugepages", "netBoot", "bootToBios", "disableGraphics",
               "noAgent", "uefi", "secureBoot", "tpm", "biosHostMode"]


def vm_record(s: dict, a: Anon, snaps) -> dict:
    cfg = s.get("config") or {}
    ctl_by_id = {c.get("id"): c for c in (s.get("controllers") or [])}
    vols = []
    for v in s.get("volumes") or []:
        c = ctl_by_id.get(g(v, "controller", "id")) or {}
        vols.append({
            "root": v.get("rootVolume"), "category": v.get("category"),
            "typeCode": g(v, "type", "code"), "diskMode": v.get("diskMode"),
            "controllerType": g(c, "type", "code"), "controllerCategory": c.get("category"),
            "maxStorage": v.get("maxStorage"), "usedStorage": v.get("usedStorage"),
            "storageProfile": v.get("storageProfile"), "imageType": v.get("imageType"),
            "diskType": v.get("diskType"), "datastore": a.tok("ds", g(v, "datastore", "name")),
            "hasSourceImage": bool(v.get("sourceImage")),
        })
    nics = []
    for n in s.get("interfaces") or []:
        mac = (n.get("macAddress") or "").lower()
        nics.append({
            "typeCode": g(n, "type", "code"), "typeName": g(n, "type", "name"),
            "mac": a.mac(mac), "vmwareOui": mac[:8] in VMWARE_OUIS,
            "ip": a.tok("ip", n.get("ipAddress")), "primary": n.get("primaryInterface"),
            "dhcp": n.get("dhcp"), "network": a.tok("net", g(n, "network", "name")),
        })
    rec = {
        "id": a.id("srv", s.get("id")),
        "name": a.tok("vm", s.get("name")),
        "serverType": g(s, "computeServerType", "code"),
        "managed": g(s, "computeServerType", "managed"),
        "cloudId": a.id("cloud", g(s, "zone", "id")),
        "host": a.tok("host", g(s, "parentServer", "name")),
        "hasInstance": bool(s.get("instance")),
        "powerState": s.get("powerState"), "status": s.get("status"),
        "osType": s.get("osType"), "serverOs": g(s, "serverOs", "code"),
        "osFamily": g(s, "serverOs", "osFamily"), "platform": s.get("platform"),
        "agentInstalled": s.get("agentInstalled"), "agentVersion": s.get("agentVersion"),
        "lastAgentUpdate": day(s.get("lastAgentUpdate")),
        "guestAgentStatus": s.get("guestAgentStatus"), "toolsInstalled": s.get("toolsInstalled"),
        "uefi": s.get("uefi"), "secureBoot": s.get("secureBoot"), "tpm": s.get("tpm"),
        "maxCores": s.get("maxCores"), "coresPerSocket": s.get("coresPerSocket"),
        "maxMemory": s.get("maxMemory"), "dateCreated": month(s.get("dateCreated")),
        "secureMetadataDatastore": a.tok("ds", g(s, "secureMetadataDatastore", "name")
                                         or s.get("secureMetadataDatastore")),
        "config": {k: cfg.get(k) for k in CONFIG_KEYS if k in cfg},
        "configHasQemuArgs": bool(cfg.get("qemuArgs")),
        "configHasAffinityGroup": bool(cfg.get("affinityGroup")),
        "volumes": vols, "controllers": [g(c, "type", "code") for c in ctl_by_id.values()],
        "interfaces": nics,
    }
    if snaps is not None:
        dates = sorted(x.get("dateCreated") for x in snaps if x.get("dateCreated"))
        rec["snapshots"] = {"count": len(snaps), "oldest": day(dates[0]) if dates else None}
    return rec


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("host", help="appliance host name (use the DNS name, not the IP)")
    ap.add_argument("--out", help="output file (default: vtu-fleet-sample-<date>.json)")
    ap.add_argument("--max-vms", type=int, default=None, help="stop after this many VMs per cloud")
    ap.add_argument("--no-snapshots", action="store_true", help="skip the per-VM snapshot lookup")
    args = ap.parse_args()

    srv = Server(args.host)
    srv.token = os.environ.get("VTU_TOKEN") or getpass.getpass("API token (read-only user is enough): ").strip()
    if not srv.token:
        sys.exit("Stopped: no token.")
    a = Anon()
    out = args.out or f"vtu-fleet-sample-{dt.date.today().isoformat()}.json"
    if os.path.exists(out):
        sys.exit(f"Stopped: {out} already exists; move it away or pass --out.")
    sample = {"format": "vm-tuneup-fleet-sample/2",
              "collectedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}

    who = srv.get("/api/whoami", kind="whoami")
    sample["appliance"] = {"buildVersion": g(who, "appliance", "buildVersion"),
                           "permissionsCount": len(who.get("permissions") or [])}
    print(f"Connected. Appliance build {sample['appliance']['buildVersion']}.")

    zones = srv.get_all("/api/zones", "zones", kind="list")
    sample["clouds"] = [{"id": a.id("cloud", z.get("id")), "name": a.tok("cloud", z.get("name")),
                         "type": g(z, "zoneType", "code")} for z in zones]
    zone_type = {z.get("id"): g(z, "zoneType", "code") for z in zones}  # raw ids, used only in memory
    print("Clouds: " + ", ".join(f"{c['type']}" for c in sample["clouds"]))

    try:
        cl = srv.get_all("/api/clusters", "clusters", kind="list")
        sample["clusters"] = [{"id": a.id("cluster", c.get("id")), "name": a.tok("cluster", c.get("name")),
                               "type": g(c, "type", "code") or g(c, "type", "name"), "cloudId": a.id("cloud", g(c, "zone", "id")),
                               "serverCount": len(c.get("servers") or [])} for c in cl]
    except ApiError as e:
        sample["clusters"] = {"error": e.status}

    try:
        mig = srv.get_all("/api/migrations", "migrations", kind="list")
        sample["migrations"] = [{
            "id": a.id("mig", m.get("id")), "status": m.get("status"), "type": m.get("type"),
            "sourceCloudId": a.id("cloud", g(m, "sourceCloud", "id")), "targetCloudId": a.id("cloud", g(m, "targetCloud", "id")),
            "installGuestTools": m.get("installGuestTools"),
            "servers": [{"status": x.get("status"),
                         "sourceServerId": a.id("srv", g(x, "sourceServer", "id")),
                         "destinationServerId": a.id("srv", g(x, "destinationServer", "id"))}
                        for x in (m.get("servers") or [])],
        } for m in mig]
    except ApiError as e:
        sample["migrations"] = {"error": e.status}

    vm_list = srv.get_all("/api/servers", "servers", {"vm": "true"}, kind="list")
    by_cloud = collections.defaultdict(list)
    for s in vm_list:
        by_cloud[g(s, "zone", "id")].append(s)
    sample["vms"] = []
    total = sum(min(len(v), args.max_vms or len(v)) for v in by_cloud.values())
    done = 0
    for cid, items in by_cloud.items():
        for s in items[: args.max_vms or len(items)]:
            done += 1
            print(f"\r  reading VM {done}/{total}", end="", flush=True)
            try:
                full = srv.get(f"/api/servers/{s['id']}", kind="serverGet").get("server") or s
            except ApiError as e:
                sample["vms"].append({"id": a.id("srv", s.get("id")), "error": e.status})
                continue
            snaps = None
            if not args.no_snapshots and zone_type.get(cid) not in ("vmware",):
                try:
                    snaps = srv.get(f"/api/servers/{s['id']}/snapshots", kind="snapshots").get("snapshots") or []
                except ApiError:
                    snaps = None
            rec = vm_record(full, a, snaps)
            rec["cloudType"] = zone_type.get(cid)
            sample["vms"].append(rec)
    print()

    sample["timings"] = {k: {"calls": len(v), "avgSec": round(sum(v) / len(v), 2), "maxSec": round(max(v), 2)}
                         for k, v in srv.timings.items() if v}

    # summary
    vms = [v for v in sample["vms"] if "error" not in v]
    vmw = [v for v in vms if v.get("cloudType") == "vmware"]
    hvm = [v for v in vms if v.get("cloudType") != "vmware"]
    C = collections.Counter
    summ = {
        "vmsByCloudType": dict(C(v.get("cloudType") for v in vms)),
        "hvm": {
            "serverType": dict(C(v["serverType"] for v in hvm)),
            "diskMode": dict(C(x["diskMode"] for v in hvm for x in v["volumes"] if x["category"] != "cd")),
            "nicType": dict(C(str(x["typeCode"]) for v in hvm for x in v["interfaces"])),
            "machineType": dict(C(str(v["config"].get("machineType")) for v in hvm)),
            "guestAgentStatus": dict(C(str(v["guestAgentStatus"]) for v in hvm)),
            "agentInstalled": dict(C(str(v["agentInstalled"]) for v in hvm)),
            "vmwareOuiMacVms": sum(1 for v in hvm if any(x["vmwareOui"] for x in v["interfaces"])),
            "uefi": dict(C(str(v["uefi"]) for v in hvm)),
        },
    }
    if vmw:
        names = {v["name"]: v for v in vmw}
        macs = {x["mac"]: v for v in vmw for x in v["interfaces"] if x["mac"]}
        ips = {x["ip"]: v for v in vmw for x in v["interfaces"] if x["ip"]}
        tw = C()
        for v in hvm:
            if v["name"] in names:
                tw["name"] += 1
            if any(x["mac"] in macs for x in v["interfaces"]):
                tw["mac"] += 1
            if any(x["ip"] in ips for x in v["interfaces"]):
                tw["ip"] += 1
        summ["possibleTwins"] = dict(tw)
    sample["summary"] = summ

    # Readable by you only, and never over an existing file (O_EXCL), whatever the umask.
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(sample, f, indent=1)
    print(json.dumps(summ, indent=1))
    print(f"\nWrote {out} ({len(vms)} VMs). Review it before sharing: it holds no names, "
          f"addresses or UUIDs in clear text, only hashed tokens, MAC vendor prefixes and settings.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\nStopped.")
    except ApiError as e:
        sys.exit(f"Stopped: the appliance answered HTTP {e.status}.")
