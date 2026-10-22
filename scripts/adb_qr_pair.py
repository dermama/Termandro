#!/usr/bin/env python3
import argparse
import secrets
import string
import subprocess
import sys
import threading
import time
from pathlib import Path

import qrcode
from zeroconf import ServiceBrowser, Zeroconf

PAIR_TYPE = "_adb-tls-pairing._tcp.local."
CONNECT_TYPE = "_adb-tls-connect._tcp.local."


def adb(*args, timeout=60):
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, timeout=timeout
    )


def pick_address(info):
    addresses = info.parsed_addresses()
    ipv4 = [address for address in addresses if ":" not in address]
    if ipv4:
        return ipv4[0]
    return addresses[0] if addresses else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--out", default="adb-pair-qr.png")
    parser.add_argument("--pair-only", action="store_true")
    args = parser.parse_args()

    alphabet = string.ascii_letters + string.digits
    service = "studio-" + "".join(secrets.choice(alphabet) for _ in range(10))
    password = "".join(secrets.choice(alphabet) for _ in range(12))
    payload = f"WIFI:T:ADB;S:{service};P:{password};;"

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=16,
        border=4,
    )
    qr.add_data(payload)
    qr.make(fit=True)
    output = Path(args.out).resolve()
    qr.make_image(fill_color="black", back_color="white").save(output)

    print(f"QR: {output}", flush=True)
    print(f"Service: {service}", flush=True)
    print(f"Password: {password}", flush=True)
    print("Scan from another screen with the target phone:", flush=True)
    print("Developer options > Wireless debugging > Pair device with QR code", flush=True)
    print(f"Waiting up to {args.timeout}s for the scan...", flush=True)

    pair_event = threading.Event()
    pair_data = {}

    class PairListener:
        def add_service(self, zc, service_type, name):
            if not service_type.startswith(PAIR_TYPE):
                return
            info = zc.get_service_info(service_type, name, timeout=3000)
            if not info:
                return
            instance = name.split(".", 1)[0]
            if instance != service:
                return
            address = pick_address(info)
            if not address:
                return
            pair_data["address"] = address
            pair_data["port"] = info.port
            pair_event.set()

        def remove_service(self, zc, service_type, name):
            pass

        def update_service(self, zc, service_type, name):
            pass

    zeroconf = Zeroconf()
    browser = ServiceBrowser(zeroconf, PAIR_TYPE, PairListener())
    try:
        if not pair_event.wait(args.timeout):
            print("Timed out waiting for the phone to scan.", flush=True)
            return 1

        endpoint = f"{pair_data['address']}:{pair_data['port']}"
        result = adb("pair", endpoint, password)
        output_text = (result.stdout + result.stderr).strip()
        print(output_text, flush=True)
        if "Successfully paired" not in result.stdout:
            return 1
        if args.pair_only:
            return 0

        print("Pairing ok; waiting for the connect service...", flush=True)
        connect_event = threading.Event()
        connect_data = {}

        class ConnectListener:
            def add_service(self, zc, service_type, name):
                if not service_type.startswith(CONNECT_TYPE):
                    return
                info = zc.get_service_info(service_type, name, timeout=3000)
                if not info:
                    return
                address = pick_address(info)
                if not address:
                    return
                connect_data["address"] = address
                connect_data["port"] = info.port
                connect_event.set()

            def remove_service(self, zc, service_type, name):
                pass

            def update_service(self, zc, service_type, name):
                pass

        connect_browser = ServiceBrowser(zeroconf, CONNECT_TYPE, ConnectListener())
        connect_event.wait(60)
        if "address" not in connect_data:
            print("Paired, but no connect service appeared.", flush=True)
            return 1

        endpoint = f"{connect_data['address']}:{connect_data['port']}"
        result = adb("connect", endpoint)
        print((result.stdout + result.stderr).strip(), flush=True)
        time.sleep(1)
        devices = adb("devices", "-l").stdout.strip()
        print(devices, flush=True)
        return 0
    finally:
        zeroconf.close()


if __name__ == "__main__":
    sys.exit(main())
