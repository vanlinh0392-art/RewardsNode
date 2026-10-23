#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script cài đặt nhanh RewardsNode APK lên thiết bị Android qua ADB Wi-Fi (192.168.1.113:5555).
Cách dùng:
  • Cài đặt APK + khởi chạy + kiểm tra trạng thái:
      python scripts/install_adb.py
  • Chỉ kiểm tra trạng thái DB & Vlog trên máy (không cài lại):
      python scripts/install_adb.py --verify-only
"""

import argparse
import contextlib
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

DEFAULT_DEVICE = "192.168.1.113:5555"
PACKAGE_NAME = "com.agy.rewardsnode"
DB_NAME = "rewards_node_database.db"
PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_APK = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
ROOT_APK_COPY = PROJECT_ROOT / "RewardsNode.apk"


def _configure_utf8_console() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def _win_creation_flags() -> int:
    if sys.platform == "win32":
        return subprocess.CREATE_NO_WINDOW
    return 0


def run_adb(args: list[str], timeout: int = 30, check: bool = False) -> subprocess.CompletedProcess:
    cmd = ["adb"] + args
    return subprocess.run(
        cmd,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=check,
        creationflags=_win_creation_flags(),
    )


def stop_adb_daemon() -> None:
    subprocess.run(
        ["adb", "kill-server"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=_win_creation_flags(),
    )


def ensure_adb_connected(device: str) -> bool:
    subprocess.run(
        ["adb", "start-server"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=_win_creation_flags(),
    )
    res = run_adb(["connect", device], timeout=10)
    out = (res.stdout + " " + res.stderr).strip()
    print(f"[ADB] connect {device}: {out}")

    devs = run_adb(["devices"], timeout=8)
    for line in devs.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[0] == device and parts[1] == "device":
            return True

    run_adb(["disconnect", device], timeout=5)
    time.sleep(0.5)
    res2 = run_adb(["connect", device], timeout=10)
    print(f"[ADB] reconnect {device}: {(res2.stdout + ' ' + res2.stderr).strip()}")
    devs2 = run_adb(["devices"], timeout=8)
    for line in devs2.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[0] == device and parts[1] == "device":
            return True
    return False


def install_via_adb_standard(device: str, apk_path: pathlib.Path, timeout: int = 90) -> bool:
    size_bytes = apk_path.stat().st_size
    print(f"[ADB-INSTALL] Đang cài đặt {apk_path.name} ({size_bytes:,} bytes) lên {device}...")
    installed_ok = False
    try:
        res = run_adb(["-s", device, "install", "-r", str(apk_path)], timeout=timeout)
        combined = (res.stdout + "\n" + res.stderr).strip()
        print(f"[ADB-INSTALL] Kết quả: {combined}")
        installed_ok = "Success" in combined
    except subprocess.TimeoutExpired as exc:
        print(f"[ADB-INSTALL] Timeout sau {timeout}s: {exc}")
    return installed_ok


def verify_and_start_app(device: str, start_service: bool = True) -> None:
    pkg_info = run_adb(["-s", device, "shell", f"dumpsys package {PACKAGE_NAME}"], timeout=10)
    for line in pkg_info.stdout.splitlines():
        stripped = line.strip()
        if stripped.startswith("versionName=") or stripped.startswith("lastUpdateTime="):
            print(f"[PACKAGE] {stripped}")

    run_adb(["-s", device, "logcat", "-c"], timeout=8)
    print("[LAUNCH] Khởi động MainActivity...")
    run_adb(["-s", device, "shell", "am", "start", "-n", f"{PACKAGE_NAME}/.ui.MainActivity"], timeout=10)

    if start_service:
        time.sleep(1.5)
        print("[SERVICE] Kích hoạt RewardsForegroundService (ACTION_START)...")
        run_adb(
            [
                "-s",
                device,
                "shell",
                "am",
                "start-foreground-service",
                "-n",
                f"{PACKAGE_NAME}/.service.RewardsForegroundService",
                "-a",
                f"{PACKAGE_NAME}.START",
            ],
            timeout=10,
        )


def inspect_device_state(device: str, wait_seconds: int = 5) -> None:
    if wait_seconds > 0:
        print(f"[VERIFY] Đợi {wait_seconds}s để kiểm tra Crash Log & Database trên thiết bị...")
        time.sleep(wait_seconds)

    crash_res = run_adb(["-s", device, "logcat", "-d", "-b", "crash"], timeout=10)
    if PACKAGE_NAME in crash_res.stdout:
        print("[CRASH-CHECK] ⚠️ Phát hiện crash trong logcat:")
        print(crash_res.stdout.strip())
    else:
        print("[CRASH-CHECK] ✅ 0 crash (Ứng dụng hoạt động ổn định, không bị văng).")

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = pathlib.Path(tmpdir)
        for suffix in ["", "-wal", "-shm"]:
            fname = f"{DB_NAME}{suffix}"
            proc = subprocess.run(
                ["adb", "-s", device, "exec-out", "run-as", PACKAGE_NAME, "cat", f"databases/{fname}"],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                timeout=10,
                creationflags=_win_creation_flags(),
            )
            if proc.returncode == 0 and proc.stdout and not proc.stdout.startswith(b"cat:"):
                (tmp_path / fname).write_bytes(proc.stdout)

        db_file = tmp_path / DB_NAME
        if db_file.exists() and db_file.read_bytes()[:15] == b"SQLite format 3":
            with contextlib.closing(sqlite3.connect(str(db_file))) as conn:
                cur = conn.cursor()
                print("\n=== TRẠNG THÁI TÀI KHOẢN TRÊN THIẾT BỊ ===")
                for row in cur.execute(
                    "SELECT email, profileName, availablePoints, pcProgress, pcMax, mobileProgress, mobileMax, readProgress, readMax, dailySetDone, claimablePoints FROM account_profiles"
                ):
                    print(
                        f"  • {row[0]} ({row[1]}): {row[2]} pts | PC={row[3]}/{row[4]} | Mob={row[5]}/{row[6]} | Read={row[7]}/{row[8]} | DailySet={row[9]} | Claim={row[10]}"
                    )
                print("\n=== 20 DÒNG VLOG MỚI NHẤT ===")
                for row in cur.execute(
                    "SELECT timeFormatted, tag, accountEmail, message, details FROM vlog_entries ORDER BY id DESC LIMIT 20"
                ):
                    print(f"  [{row[0]}] [{row[1]}] ({row[2] or 'SYS'}) {row[3]} | {row[4] or ''}")


def main() -> int:
    _configure_utf8_console()
    parser = argparse.ArgumentParser(description="Cài đặt và kiểm chứng RewardsNode APK qua ADB 192.168.1.113")
    parser.add_argument("--device", default=DEFAULT_DEVICE, help="Địa chỉ ADB thiết bị (mặc định: 192.168.1.113:5555)")
    parser.add_argument("--apk", default=str(DEFAULT_APK), help="Đường dẫn tới file APK")
    parser.add_argument("--no-start", action="store_true", help="Chỉ cài đặt, không tự chạy Node")
    parser.add_argument("--verify-only", action="store_true", help="Chỉ kiểm tra trạng thái Crash Log & DB trên thiết bị")
    parser.add_argument("--wait", type=int, default=8, help="Số giây đợi kiểm tra trạng thái sau khi chạy")
    args = parser.parse_args()

    exit_code = 0
    try:
        if not ensure_adb_connected(args.device):
            print(f"❌ Không thể kết nối ADB tới {args.device}. Vui lòng kiểm tra Wi-Fi/Wireless Debugging.")
            exit_code = 2
        elif args.verify_only:
            inspect_device_state(args.device, wait_seconds=0)
        else:
            apk_path = pathlib.Path(args.apk).resolve()
            if not apk_path.exists():
                print(f"❌ Không tìm thấy file APK tại: {apk_path}")
                exit_code = 1
            else:
                try:
                    shutil.copy2(apk_path, ROOT_APK_COPY)
                except OSError as copy_err:
                    print(f"[WARN] Không thể copy bản sao ra {ROOT_APK_COPY.name}: {copy_err}")

                if not install_via_adb_standard(args.device, apk_path):
                    print("❌ Cài đặt APK thất bại.")
                    exit_code = 3
                else:
                    print("✅ Cài đặt APK thành công!")
                    verify_and_start_app(args.device, start_service=not args.no_start)
                    if not args.no_start and args.wait > 0:
                        inspect_device_state(args.device, wait_seconds=args.wait)
    finally:
        stop_adb_daemon()

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
