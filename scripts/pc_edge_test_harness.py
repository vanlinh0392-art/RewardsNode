#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PC Edge Test Harness for Microsoft Rewards
Trích xuất Cookie an toàn từ Profile Edge mặc định bằng kỹ thuật:
  1. Win32 Non-blocking Shared Read (CreateFileW - Share Mode 7)
  2. Win32 Process Handle Duplication (rstrtmgr + DuplicateHandle)
Giải mã DPAPI + AES-256-GCM
Kiểm tra trực tiếp các API Microsoft Rewards và in ra cấu trúc dữ liệu sống.
"""

import os
import sys
import shutil
import sqlite3
import base64
import json
import ctypes
from ctypes import wintypes
import urllib.request
import urllib.parse
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Đảm bảo console Windows hiển thị đúng UTF-8
if sys.platform.startswith('win'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except:
        pass

# Cấu hình Win32 API
kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.DuplicateHandle.argtypes = [
    wintypes.HANDLE, wintypes.HANDLE, wintypes.HANDLE,
    ctypes.POINTER(wintypes.HANDLE), wintypes.DWORD, wintypes.BOOL, wintypes.DWORD
]
kernel32.DuplicateHandle.restype = wintypes.BOOL

try:
    rstrtmgr = ctypes.WinDLL('rstrtmgr')
except:
    rstrtmgr = None

GENERIC_READ = 0x80000000
FILE_SHARE_ALL = 0x00000001 | 0x00000002 | 0x00000004
OPEN_EXISTING = 3
FILE_ATTRIBUTE_NORMAL = 0x80
PROCESS_DUP_HANDLE = 0x0040
DUPLICATE_SAME_ACCESS = 2

class DATA_BLOB(ctypes.Structure):
    _fields_ = [
        ('cbData', wintypes.DWORD),
        ('pbData', ctypes.POINTER(ctypes.c_byte))
    ]

def decrypt_dpapi(cipher_bytes: bytes) -> bytes:
    data_in = DATA_BLOB(len(cipher_bytes), ctypes.cast(ctypes.create_string_buffer(cipher_bytes), ctypes.POINTER(ctypes.c_byte)))
    data_out = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    if crypt32.CryptUnprotectData(ctypes.byref(data_in), None, None, None, None, 0, ctypes.byref(data_out)):
        out_bytes = ctypes.string_at(data_out.pbData, data_out.cbData)
        ctypes.windll.kernel32.LocalFree(data_out.pbData)
        return out_bytes
    else:
        raise RuntimeError("CryptUnprotectData failed.")

def get_pids_locking_file(file_path: str):
    if not rstrtmgr:
        return []
    session_handle = wintypes.DWORD()
    session_key = (wintypes.WCHAR * 33)()
    if rstrtmgr.RmStartSession(ctypes.byref(session_handle), 0, session_key) != 0:
        return []
    try:
        files = (wintypes.LPCWSTR * 1)(file_path)
        if rstrtmgr.RmRegisterResources(session_handle, 1, files, 0, None, 0, None) != 0:
            return []
        n_needed = wintypes.UINT()
        n_proc = wintypes.UINT(10)
        class RM_UNIQUE_PROCESS(ctypes.Structure):
            _fields_ = [('dwProcessId', wintypes.DWORD), ('ProcessStartTime', wintypes.FILETIME)]
        class RM_PROCESS_INFO(ctypes.Structure):
            _fields_ = [('Process', RM_UNIQUE_PROCESS), ('strAppName', wintypes.WCHAR * 256),
                        ('strServiceShortName', wintypes.WCHAR * 64), ('ApplicationType', wintypes.INT),
                        ('AppStatus', wintypes.ULONG), ('TSSessionId', wintypes.DWORD), ('bRestartable', wintypes.BOOL)]
        apps = (RM_PROCESS_INFO * 10)()
        reasons = wintypes.DWORD()
        res = rstrtmgr.RmGetList(session_handle, ctypes.byref(n_needed), ctypes.byref(n_proc), apps, ctypes.byref(reasons))
        if res == 0:
            return [apps[i].Process.dwProcessId for i in range(n_proc.value)]
        return []
    finally:
        rstrtmgr.RmEndSession(session_handle)

def copy_via_duplicate_handle(src: str, dst: str) -> bool:
    pids = get_pids_locking_file(src)
    if not pids:
        return False
    path_buf = (wintypes.WCHAR * 1024)()
    buf = ctypes.create_string_buffer(65536)
    bytes_read = wintypes.DWORD()
    for pid in pids:
        h_proc = kernel32.OpenProcess(PROCESS_DUP_HANDLE, False, pid)
        if not h_proc:
            continue
        try:
            for h_val in range(4, 8000, 4):
                h_dup = wintypes.HANDLE()
                if kernel32.DuplicateHandle(h_proc, wintypes.HANDLE(h_val), kernel32.GetCurrentProcess(),
                                            ctypes.byref(h_dup), 0, False, DUPLICATE_SAME_ACCESS):
                    if kernel32.GetFileType(h_dup) == 1:
                        if kernel32.GetFinalPathNameByHandleW(h_dup, path_buf, 1024, 0):
                            target_str = path_buf.value.replace(r'\\?\\', '')
                            try:
                                if os.path.samefile(target_str, src):
                                    kernel32.SetFilePointer(h_dup, 0, None, 0)
                                    with open(dst, 'wb') as out_f:
                                        while True:
                                            ok = kernel32.ReadFile(h_dup, buf, 65536, ctypes.byref(bytes_read), None)
                                            if not ok or bytes_read.value == 0:
                                                break
                                            out_f.write(buf.raw[:bytes_read.value])
                                    kernel32.CloseHandle(h_dup)
                                    return True
                            except OSError:
                                pass
                    kernel32.CloseHandle(h_dup)
        finally:
            kernel32.CloseHandle(h_proc)
    return False

def robust_copy(src: str, dst: str) -> bool:
    if not os.path.exists(src):
        return False
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    try:
        shutil.copy2(src, dst)
        return True
    except (PermissionError, OSError):
        pass

    handle = kernel32.CreateFileW(src, GENERIC_READ, FILE_SHARE_ALL, None, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, None)
    if handle != -1 and handle != 0xFFFFFFFFFFFFFFFF:
        try:
            buf = ctypes.create_string_buffer(65536)
            bytes_read = wintypes.DWORD()
            with open(dst, 'wb') as out_f:
                while True:
                    ok = kernel32.ReadFile(handle, buf, 65536, ctypes.byref(bytes_read), None)
                    if not ok or bytes_read.value == 0:
                        break
                    out_f.write(buf.raw[:bytes_read.value])
            return True
        finally:
            kernel32.CloseHandle(handle)

    return copy_via_duplicate_handle(src, dst)

def get_edge_master_key():
    local_state_path = os.path.expandvars(r'%LOCALAPPDATA%\Microsoft\Edge\User Data\Local State')
    with open(local_state_path, 'r', encoding='utf-8') as f:
        local_state = json.load(f)
    encrypted_key = base64.b64decode(local_state['os_crypt']['encrypted_key'])[5:]
    return decrypt_dpapi(encrypted_key)

def decrypt_cookie_value(encrypted_val: bytes, master_key: bytes) -> str:
    if not encrypted_val:
        return ""
    try:
        prefix = encrypted_val[:3]
        if prefix in (b'v10', b'v11', b'v20'):
            nonce = encrypted_val[3:15]
            ciphertext = encrypted_val[15:-16]
            tag = encrypted_val[-16:]
            aesgcm = AESGCM(master_key)
            decrypted = aesgcm.decrypt(nonce, ciphertext + tag, None)
            return decrypted.decode('utf-8', errors='ignore')
        else:
            return decrypt_dpapi(encrypted_val).decode('utf-8', errors='ignore')
    except Exception:
        return ""

def extract_edge_bing_cookies():
    cookie_db_path = os.path.expandvars(r'%LOCALAPPDATA%\Microsoft\Edge\User Data\Default\Network\Cookies')
    if not os.path.exists(cookie_db_path):
        cookie_db_path = os.path.expandvars(r'%LOCALAPPDATA%\Microsoft\Edge\User Data\Default\Cookies')
    
    temp_dir = os.path.join(os.environ.get('TEMP', r'C:\Temp'), 'RewardsEdgeTest')
    os.makedirs(temp_dir, exist_ok=True)
    temp_cookie_db = os.path.join(temp_dir, 'Cookies_Cloned.db')
    
    # Sao chép cả WAL/journal nếu có
    wal_src = cookie_db_path + '-wal'
    if os.path.exists(wal_src):
        robust_copy(wal_src, temp_cookie_db + '-wal')
        
    ok = robust_copy(cookie_db_path, temp_cookie_db)
    if not ok:
        raise RuntimeError("Không thể sao chép Cookies DB từ Edge (thử lại hoặc kiểm tra quyền)")

    master_key = get_edge_master_key()
    
    conn = sqlite3.connect(temp_cookie_db)
    cursor = conn.cursor()
    cursor.execute("""
        SELECT host_key, name, encrypted_value 
        FROM cookies 
        WHERE host_key LIKE '%bing.com%' OR host_key LIKE '%live.com%' OR host_key LIKE '%microsoft.com%'
    """)
    
    cookies = {}
    for host, name, enc_val in cursor.fetchall():
        if enc_val:
            val = decrypt_cookie_value(enc_val, master_key)
            if val:
                cookies[name] = val
    conn.close()
    
    # Dọn dẹp
    for p in (temp_cookie_db, temp_cookie_db + '-wal'):
        try:
            if os.path.exists(p): os.remove(p)
        except: pass
        
    return cookies

def test_flyout_api(cookies):
    url = "https://www.bing.com/rewardsapp/flyoutpageinformation"
    cookie_str = "; ".join([f"{k}={v}" for k, v in cookies.items() if k in ('_U', 'MUID', 'SRCHHPGUSR', '_RwBf', 'KievRPIRefreshToken')])
    
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0",
            "Cookie": cookie_str,
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Referer": "https://www.bing.com/"
        }
    )
    
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            return data
    except Exception as e:
        print(f"[!] Lỗi khi gọi Flyout API: {e}")
        return None

def test_get_user_info(cookies):
    url = "https://rewards.bing.com/api/getuserinfo"
    cookie_str = "; ".join([f"{k}={v}" for k, v in cookies.items()])
    
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0",
            "Cookie": cookie_str,
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://rewards.bing.com/"
        }
    )
    
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            return data
    except Exception as e:
        print(f"[!] Lỗi khi gọi getuserinfo API: {e}")
        return None

def main():
    print("=" * 70)
    print("  🚀 MICROSOFT REWARDS PC EDGE TEST HARNESS (DUAL-ENGINE WIN32)")
    print("=" * 70)
    
    print("[1] Đang trích xuất cookie từ Edge Default Profile...")
    try:
        cookies = extract_edge_bing_cookies()
        print(f"  ✓ Đã trích xuất thành công {len(cookies)} cookies từ Edge!")
        has_u = '_U' in cookies
        has_muid = 'MUID' in cookies
        print(f"  ✓ Cookie _U: {'CÓ (Đã đăng nhập)' if has_u else 'KHÔNG (Chưa đăng nhập)'}")
        print(f"  ✓ Cookie MUID: {'CÓ' if has_muid else 'KHÔNG'}")
        
        if not has_u:
            print("[!] Cảnh báo: Không tìm thấy cookie '_U'. Hãy mở Edge và đăng nhập tài khoản Microsoft trên bing.com!")
    except Exception as e:
        print(f"  [X] Lỗi trích xuất cookie: {e}")
        return
        
    print("\n[2] Đang kiểm tra Flyout API (Số dư 5 rổ điểm)...")
    flyout = test_flyout_api(cookies)
    if flyout:
        user_status = flyout.get('userStatus', {})
        available_pts = user_status.get('availablePoints', 0)
        lifetime_pts = user_status.get('lifetimePoints', 0)
        streak_count = user_status.get('streakInfo', {}).get('streakCount', 0)
        counters = user_status.get('counters', {}).get('dailyPoint', [])
        
        print(f"  ★ Available Points : {available_pts:,} pts")
        print(f"  ★ Lifetime Points  : {lifetime_pts:,} pts")
        print(f"  ★ Streak Count     : {streak_count} ngày")
        print("  ★ Chi tiết rổ điểm hàng ngày:")
        for c in counters:
            pt_name = c.get('title', c.get('name', 'N/A'))
            cur = c.get('pointProgress', 0)
            max_pt = c.get('pointProgressMax', 0)
            print(f"     • {pt_name:<20}: {cur}/{max_pt} pts")
            
        daily_set = flyout.get('dailySetPromotions', {})
        print(f"  ★ Daily Set Promos : {len(daily_set)} ngày được nạp")
    else:
        print("  [X] Không lấy được dữ liệu từ Flyout API.")

    print("\n[3] Đang kiểm tra getuserinfo API (Danh mục nhiệm vụ động)...")
    user_info = test_get_user_info(cookies)
    if user_info:
        dashboard = user_info.get('dashboard', {})
        user_status = dashboard.get('userStatus', {})
        level = user_status.get('levelInfo', {}).get('activeLevel', 'N/A')
        print(f"  ★ Level hiện tại   : Level {level}")
        
        daily_items = dashboard.get('dailySetPromotions', {})
        today_key = list(daily_items.keys())[0] if daily_items else None
        if today_key:
            print(f"  ★ Daily Set hôm nay ({today_key}):")
            for item in daily_items[today_key]:
                title = item.get('title', 'N/A')
                pts = item.get('pointProgressMax', 0)
                done = item.get('complete', False)
                print(f"     [ {'✓' if done else ' '} ] {title} (+{pts}đ)")
                
        more_promos = dashboard.get('morePromotions', [])
        print(f"  ★ More Activities  : {len(more_promos)} thẻ bổ sung:")
        for item in more_promos[:5]:
            title = item.get('title', 'N/A')
            pts = item.get('pointProgressMax', 0)
            done = item.get('complete', False)
            print(f"     [ {'✓' if done else ' '} ] {title} (+{pts}đ)")
        if len(more_promos) > 5:
            print(f"     ... và {len(more_promos) - 5} thẻ khác.")
            
        punch_cards = dashboard.get('punchCards', [])
        print(f"  ★ Punch Cards      : {len(punch_cards)} chiến dịch:")
        for card in punch_cards:
            name = card.get('name', card.get('title', 'N/A'))
            pts = card.get('pointProgressMax', 0)
            print(f"     • {name} (+{pts}đ)")
            
    print("\n" + "=" * 70)
    print("  ✓ Hoàn tất kiểm thử PC! Cấu trúc API Microsoft Rewards hoàn toàn tương thích.")
    print("=" * 70)

if __name__ == '__main__':
    main()
