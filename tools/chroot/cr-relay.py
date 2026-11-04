#!/usr/bin/python3
"""Runs a login shell on its own pty and relays it over stdin/stdout.

Frames both ways: 1 byte kind, 4 byte big-endian length, payload.
In: D keystrokes, R rows and cols. Out: D screen output, X exit status.
"""
import fcntl
import os
import pty
import select
import struct
import sys
import termios


def send(kind, payload):
    data = kind + struct.pack(">i", len(payload)) + payload
    while data:
        data = data[os.write(1, data):]


def main():
    pid, master = pty.fork()
    if pid == 0:
        try:
            os.chdir(os.environ.get("HOME", "/"))
        except OSError:
            pass
        os.execv("/bin/bash", ["-bash"])
    buf = b""
    status = 1
    stdin_open = True
    while True:
        ready, _, _ = select.select([master] + ([0] if stdin_open else []), [], [])
        if master in ready:
            try:
                out = os.read(master, 65536)
            except OSError:
                out = b""
            if not out:
                break
            send(b"D", out)
        if 0 in ready:
            more = os.read(0, 65536)
            if not more:
                stdin_open = False
                os.kill(pid, 1)
                continue
            buf += more
            while len(buf) >= 5:
                size = struct.unpack(">i", buf[1:5])[0]
                if len(buf) < 5 + size:
                    break
                kind, payload, buf = buf[:1], buf[5:5 + size], buf[5 + size:]
                if kind == b"D":
                    os.write(master, payload)
                elif kind == b"R" and size == 4:
                    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", *struct.unpack(">HH", payload), 0, 0))
    _, code = os.waitpid(pid, 0)
    status = os.waitstatus_to_exitcode(code)
    send(b"X", str(status if status >= 0 else 128 - status).encode())


if __name__ == "__main__":
    sys.exit(main())
