#!/usr/bin/env python3
import sys
import os
import re
import socket
import subprocess
import urllib.parse
import urllib.request
import json
import time

CACHE_FILE = os.path.expanduser("~/.warpshare_ip")
PORT = 8888

def notify(title, message, sound="Glass"):
    apple_script = f'''display notification "{message}" with title "{title}" sound name "{sound}"'''
    subprocess.run(["osascript", "-e", apple_script], check=False)

def prompt_ip():
    script = '''
    set userIP to ""
    try
        set dialogResult to display dialog "WarpShare di perangkat Android belum terdeteksi otomatis.\nMasukkan alamat IP Android (lihat di aplikasi WarpShare):" default answer "192.168.18.13" with title "Kirim ke WarpShare" buttons {"Batal", "Kirim"} default button "Kirim"
        if button returned of dialogResult is "Kirim" then
            set userIP to text returned of dialogResult
        end if
    end try
    return userIP
    '''
    res = subprocess.run(["osascript", "-e", script], capture_output=True, text=True, check=False)
    return res.stdout.strip()

def check_ip(ip):
    try:
        url = f"http://{ip}:{PORT}/api/info"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode())
                return data.get("name", "Android Device")
    except Exception:
        return None
    return None

def discover_ip():
    # 1. Resolve zero-config mDNS hostname (warpshare.local) directly to IPv4 (3ms)
    try:
        resolved_ip = socket.gethostbyname("warpshare.local")
        if resolved_ip and check_ip(resolved_ip):
            with open(CACHE_FILE, "w") as f:
                f.write(resolved_ip)
            return resolved_ip
    except Exception:
        pass

    # 2. Check cache
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r") as f:
                cached = f.read().strip()
            if cached and check_ip(cached):
                return cached
        except Exception:
            pass

    # 3. Try mDNS discovery via dns-sd browsing
    try:
        proc = subprocess.Popen(["dns-sd", "-B", "_airdrop._tcp", "."], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(1.0)
        proc.terminate()
        out, _ = proc.communicate()
        
        instances = []
        for line in out.splitlines():
            if "Add" in line and "_airdrop._tcp." in line:
                parts = line.split()
                if parts:
                    instances.append(parts[-1])
                    
        for inst in instances:
            lookup = subprocess.Popen(["dns-sd", "-L", inst, "_airdrop._tcp", "local."], stdout=subprocess.PIPE, text=True)
            time.sleep(0.6)
            lookup.terminate()
            l_out, _ = lookup.communicate()
            m = re.search(r'can be reached at ([\w\.\-]+):', l_out)
            if m:
                host = m.group(1).rstrip(".")
                try:
                    ip = socket.gethostbyname(host)
                    if check_ip(ip):
                        with open(CACHE_FILE, "w") as f:
                            f.write(ip)
                        return ip
                except Exception:
                    pass
    except Exception:
        pass

    # 3. Prompt user if auto-discovery fails
    manual_ip = prompt_ip()
    if manual_ip:
        with open(CACHE_FILE, "w") as f:
            f.write(manual_ip)
        return manual_ip

    return None

def upload_file(ip, filepath):
    filename = os.path.basename(filepath)
    encoded_name = urllib.parse.quote(filename, safe='')
    url = f"http://{ip}:{PORT}/api/upload?name={encoded_name}"
    
    # Use native macOS /usr/bin/curl for zero-memory kernel streaming of any file size
    # Provide triple-redundant filename headers (URL query, X-File-Name, Content-Disposition)
    res = subprocess.run([
        "/usr/bin/curl",
        "-s",
        "-S",
        "-o", "/dev/null",
        "-w", "%{http_code}",
        "--connect-timeout", "3",
        "-H", "Content-Type: application/octet-stream",
        "-H", f"X-File-Name: {encoded_name}",
        "-H", f"Content-Disposition: attachment; filename=\"{filename}\"",
        "--data-binary", f"@{filepath}",
        url
    ], capture_output=True, text=True, check=False)
    
    return res.stdout.strip() == "200"

def main():
    files = sys.argv[1:]
    if not files:
        notify("WarpShare", "Tidak ada file yang dipilih.", sound="Basso")
        return

    # Filter existing files
    valid_files = [f for f in files if os.path.isfile(f)]
    if not valid_files:
        notify("WarpShare", "File yang dipilih tidak valid atau berupa direktori.", sound="Basso")
        return

    notify("WarpShare", f"Mencari perangkat Android...", sound="")
    target_ip = discover_ip()
    if not target_ip:
        notify("WarpShare", "Gagal menemukan perangkat Android WarpShare.", sound="Basso")
        return

    device_name = check_ip(target_ip) or "Android"
    success_count = 0
    total = len(valid_files)

    for i, filepath in enumerate(valid_files, start=1):
        try:
            fname = os.path.basename(filepath)
            if total > 1:
                notify("WarpShare", f"Mengirim ({i}/{total}): {fname}...", sound="")
            if upload_file(target_ip, filepath):
                success_count += 1
        except Exception as e:
            print(f"Error uploading {filepath}: {e}", file=sys.stderr)

    if success_count == total:
        msg = f"Berhasil mengirim {total} file ke {device_name}!" if total > 1 else f"Berhasil mengirim {os.path.basename(valid_files[0])} ke {device_name}!"
        notify("WarpShare Selesai", msg, sound="Glass")
    else:
        notify("WarpShare", f"Terkirim {success_count} dari {total} file ke {device_name}.", sound="Basso")

if __name__ == "__main__":
    main()
