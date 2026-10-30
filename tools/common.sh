# Shared by install.sh, design.sh and undo.sh.

# Find the box's ADB port unless TV_PORT is already set. Most devices use 5555, and some firmware picks its own.
detect_port() {
  [ -n "${USER_TV_PORT:-}" ] && { export TV_PORT="$USER_TV_PORT"; return 0; }
  TV_PORT=$("$PY" -I - "$1" <<'PY'
import socket
import sys

for port in (5555, 3222):
    s = socket.socket()
    s.settimeout(2)
    try:
        s.connect((sys.argv[1], port))
        print(port)
        break
    except OSError:
        pass
    finally:
        s.close()
else:
    print(5555)
PY
)
  export TV_PORT
}
