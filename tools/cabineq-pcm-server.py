#!/usr/bin/env python3
"""PCM server for the Soloist source: owns the pipe sink's FIFO, serves the app.

Why this exists instead of socat: socat reads the FIFO only after a client connects, so with
nobody listening the pipe fills, the pipe sink blocks and Soloist freezes (Spotify shows the
device and sits at 0:00). It also starts a new reader per connection, which can begin on an odd
byte offset of a raw s16 stream — that swaps the channels for the whole session.

So one process owns the FIFO for the session's lifetime:

  * nobody connected  -> keep draining at exactly real time, so Soloist and Spotify keep running;
  * a client connected -> send to it with blocking writes, so a slow or paused client pushes back
                          through the socket, into the pipe, and paces Soloist by the client's
                          clock. That backpressure is what replaces the drift correction.

The FIFO is opened read/write, which never blocks on open and never reports end-of-file when the
writer restarts. Reads are trimmed to whole 4-byte frames, and the stream is never reopened, so
sample alignment is fixed for the whole session.
"""
import errno
import os
import socket
import sys
import time

FIFO = sys.argv[1]
PORT = int(sys.argv[2])
BYTES_PER_SECOND = 44100 * 2 * 2          # s16le, stereo
FRAME = 4


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def main():
    if not os.path.exists(FIFO):
        os.mkfifo(FIFO, 0o600)
    fifo = os.open(FIFO, os.O_RDWR)       # no writer needed, and no EOF when Soloist restarts

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(2)
    srv.setblocking(False)
    log("serving %s on 127.0.0.1:%d" % (FIFO, PORT))

    client = None
    while True:
        data = os.read(fifo, 65536)
        if not data:
            time.sleep(0.01)
            continue
        data = data[: len(data) - len(data) % FRAME]

        # Take a waiting client, and drop one that has gone away.
        if client is None:
            try:
                client, addr = srv.accept()
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                log("client connected")
            except BlockingIOError:
                pass

        if client is not None:
            try:
                client.sendall(data)      # blocks: that is the backpressure
                continue
            except OSError as e:
                log("client gone (%s)" % e.__class__.__name__)
                try:
                    client.close()
                except OSError:
                    pass
                client = None

        # Nobody listening: drain at real time so the producer keeps its pace and Spotify keeps
        # playing (the app can attach at the live edge later).
        time.sleep(len(data) / BYTES_PER_SECOND)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except BrokenPipeError:
        pass
    except OSError as e:
        if e.errno != errno.EPIPE:
            raise
