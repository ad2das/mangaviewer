import os
import socket
import socketserver
import struct
import sys
import threading
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
NOSPLIT = os.environ.get("NOSPLIT") == "1"


def sni_split(body):
    try:
        cursor = 4 + 2 + 32
        cursor += 1 + body[cursor]
        cursor += 2 + ((body[cursor] << 8) | body[cursor + 1])
        cursor += 1 + body[cursor]
        end = cursor + 2 + ((body[cursor] << 8) | body[cursor + 1])
        cursor += 2
        if end > len(body):
            return None
        while cursor + 4 <= end:
            rtype = (body[cursor] << 8) | body[cursor + 1]
            rlen = (body[cursor + 2] << 8) | body[cursor + 3]
            value = cursor + 4
            if value + rlen > end:
                return None
            if rtype == 0 and rlen >= 6 and body[value + 2] == 0:
                hostlen = (body[value + 3] << 8) | body[value + 4]
                if hostlen > 1 and value + 5 + hostlen <= value + rlen:
                    return value + 5 + hostlen // 2
            cursor = value + rlen
    except Exception:
        return None
    return None


def read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("eof")
        data += chunk
    return data


def handle(client, addr):
    try:
        client.settimeout(20)
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            header += read_exact(client, 1)
            if len(header) > 8192:
                raise ValueError("header too big")
        lines = header.decode("latin-1").split("\r\n")
        parts = lines[0].split(" ")
        host, port = parts[1].rsplit(":", 1)
        port = int(port)
        remote = socket.create_connection((host, port), timeout=10)
        remote.settimeout(20)
        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        rec_header = read_exact(client, 5)
        rec_len = (rec_header[3] << 8) | rec_header[4]
        rec_body = read_exact(client, rec_len)
        split = None
        if rec_header[0] == 22 and rec_body and rec_body[0] == 1 and not NOSPLIT:
            split = sni_split(rec_body)
        if split:
            print(f"SPLIT {host}:{port} len={rec_len} split={split}", flush=True)
            first = rec_header[:3] + bytes([split >> 8, split & 255]) + rec_body[:split]
            second = rec_header[:3] + bytes([(rec_len - split) >> 8, (rec_len - split) & 255]) + rec_body[split:]
            remote.sendall(first)
            time.sleep(0.05)
            remote.sendall(second)
        else:
            print(f"PASS {host}:{port} len={rec_len}", flush=True)
            remote.sendall(rec_header + rec_body)

        def pump(src, dst):
            try:
                while True:
                    chunk = src.recv(65536)
                    if not chunk:
                        break
                    dst.sendall(chunk)
            except Exception:
                pass
            finally:
                try:
                    dst.shutdown(socket.SHUT_WR)
                except Exception:
                    pass

        t = threading.Thread(target=pump, args=(remote, client), daemon=True)
        t.start()
        pump(client, remote)
        t.join(timeout=5)
    except Exception as e:
        print(f"ERR {addr}: {e}", flush=True)
    finally:
        try:
            client.close()
        except Exception:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        handle(self.request, self.client_address)


print(f"RELAY2 listening on 0.0.0.0:{PORT}", flush=True)
Server(("0.0.0.0", PORT), Handler).serve_forever()
