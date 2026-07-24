#!/usr/bin/env python3
"""
ExampleSMP Admin TUI — a terminal control panel for server admins.

Modules:
  - Dashboard       : server status, online count, recent activity
  - Players         : search a player -> profile, balance, transactions, flags, punishments
  - Punishments     : view active punishments, issue ban/mute/kick, revoke
  - Anticheat       : recent GrimAC flags (anticheat_violations)
  - Audit           : transaction audit log + per-player /sell history
  - Server          : broadcast / save-all / restart / raw console command

Control plane:
  - Reads MariaDB directly via the `mysql` CLI (no python deps required).
  - Sends console commands by injecting into the paper `screen` session.

Run:  python3 tools/admin-tui/pizzactl.py
"""
import curses
import subprocess
import os
import sys
import re
import textwrap

# ---- Config -----------------------------------------------------------------
DB_HOST = "127.0.0.1"
DB_USER = "pizzasmp"
DB_PASS = "changeme"
DB_NAME = "pizzasmp"
SCREEN_HINT = "pizzasmp"   # screen session name substring that runs paper
SERVER_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

MENU = ["Dashboard", "Players", "Economy", "Punishments", "Anticheat",
        "Audit", "Online", "Logs", "Maintenance", "Server", "Quit"]


# ---- Data plane -------------------------------------------------------------
def db_query(sql):
    """Run a SQL query, return list of rows (each row = list of column strings)."""
    try:
        out = subprocess.run(
            ["mysql", "-h", DB_HOST, "-u", DB_USER, f"-p{DB_PASS}", DB_NAME, "-N", "-B", "-e", sql],
            capture_output=True, text=True, timeout=15,
        )
        if out.returncode != 0:
            return [["ERROR", out.stderr.strip()[:120]]]
        rows = []
        for line in out.stdout.splitlines():
            rows.append(line.split("\t"))
        return rows
    except Exception as e:
        return [["ERROR", str(e)[:120]]]


def db_scalar(sql, default="?"):
    rows = db_query(sql)
    if rows and rows[0] and rows[0][0] != "ERROR":
        return rows[0][0]
    return default


def esc(s):
    """Escape a string for safe single-quoted SQL embedding."""
    return s.replace("\\", "\\\\").replace("'", "''")


# ---- Console plane ----------------------------------------------------------
def find_screen_session():
    try:
        out = subprocess.run(["screen", "-ls"], capture_output=True, text=True, timeout=5).stdout
        matches = re.findall(r"(\d+\.\S*" + re.escape(SCREEN_HINT) + r"\S*)", out)
        return matches[0] if matches else None
    except Exception:
        return None


def server_pid():
    try:
        out = subprocess.run(["pgrep", "-f", "paper.jar"], capture_output=True, text=True, timeout=5).stdout
        return out.strip().splitlines()[0] if out.strip() else None
    except Exception:
        return None


def send_console(cmd):
    sess = find_screen_session()
    if not sess:
        return False, "No paper screen session found"
    try:
        subprocess.run(["screen", "-S", sess, "-p", "0", "-X", "stuff", cmd + "\r"], timeout=5)
        return True, f"Sent to {sess}: {cmd}"
    except Exception as e:
        return False, str(e)


SCRIPTS_DIR = os.path.join(SERVER_DIR, "scripts")
_ANSI = re.compile(r"\033\[[0-9;]*m")


def run_script(name, *args, timeout=120):
    """Run scripts/<name> and return (ok, last_meaningful_output_line)."""
    path = os.path.join(SCRIPTS_DIR, name)
    if not os.path.exists(path):
        return False, f"missing: scripts/{name}"
    try:
        r = subprocess.run(["bash", path, *args], capture_output=True, text=True,
                            timeout=timeout, cwd=SERVER_DIR)
        out = _ANSI.sub("", (r.stdout or "") + (r.stderr or "")).strip().splitlines()
        last = out[-1] if out else "(no output)"
        return r.returncode == 0, last
    except Exception as e:
        return False, str(e)


# ---- UI helpers -------------------------------------------------------------
def safe_addstr(win, y, x, text, attr=0):
    h, w = win.getmaxyx()
    if y < 0 or y >= h or x >= w:
        return
    win.addstr(y, x, text[: max(0, w - x - 1)], attr)


def prompt(stdscr, label):
    curses.echo()
    curses.curs_set(1)
    h, w = stdscr.getmaxyx()
    win = curses.newwin(3, w - 4, h - 4, 2)
    win.box()
    safe_addstr(win, 0, 2, f" {label} ")
    win.refresh()
    win.move(1, 2)
    try:
        val = win.getstr(1, 2, w - 10).decode("utf-8", "ignore").strip()
    except Exception:
        val = ""
    curses.noecho()
    curses.curs_set(0)
    return val


def pager(stdscr, title, lines):
    """Scrollable read-only view of `lines`."""
    top = 0
    while True:
        stdscr.erase()
        h, w = stdscr.getmaxyx()
        safe_addstr(stdscr, 0, 2, f" {title} ", curses.A_REVERSE | curses.A_BOLD)
        safe_addstr(stdscr, h - 1, 2, "↑/↓ PgUp/PgDn scroll · q/Esc back", curses.A_DIM)
        view = lines[top:top + h - 3]
        for i, ln in enumerate(view):
            safe_addstr(stdscr, 2 + i, 2, ln)
        stdscr.refresh()
        k = stdscr.getch()
        if k in (ord("q"), 27):
            return
        elif k in (curses.KEY_DOWN, ord("j")):
            top = min(max(0, len(lines) - 1), top + 1)
        elif k in (curses.KEY_UP, ord("k")):
            top = max(0, top - 1)
        elif k == curses.KEY_NPAGE:
            top = min(max(0, len(lines) - 1), top + (h - 4))
        elif k == curses.KEY_PPAGE:
            top = max(0, top - (h - 4))


def confirm(stdscr, msg):
    ans = prompt(stdscr, msg + " (type YES)")
    return ans == "YES"


# ---- Autocomplete + aggregation --------------------------------------------
_NAME_CACHE = None


def all_player_names(refresh=False):
    global _NAME_CACHE
    if _NAME_CACHE is None or refresh:
        rows = db_query("SELECT username FROM players ORDER BY last_seen DESC")
        _NAME_CACHE = [r[0] for r in rows if r and r[0] and r[0] != "ERROR"]
    return _NAME_CACHE


def prompt_player(stdscr, label="Player name"):
    """Input box with live player-name suggestions. Tab completes, Enter accepts, Esc cancels."""
    names = all_player_names()
    buf = ""
    sugg_idx = 0
    curses.curs_set(1)
    while True:
        h, w = stdscr.getmaxyx()
        # suggestions: prefix matches first, then substring
        low = buf.lower()
        pref = [n for n in names if n.lower().startswith(low)]
        sub = [n for n in names if low in n.lower() and n not in pref]
        matches = (pref + sub)[:8]
        if sugg_idx >= len(matches):
            sugg_idx = 0
        win = curses.newwin(min(12, 3 + len(matches)), w - 4, h - (3 + min(9, len(matches) + 1)), 2)
        win.erase()
        win.box()
        safe_addstr(win, 0, 2, f" {label} (Tab=complete · Enter=ok · Esc=cancel) ")
        safe_addstr(win, 1, 2, "> " + buf)
        for i, m in enumerate(matches):
            attr = curses.A_REVERSE if i == sugg_idx else curses.A_DIM
            safe_addstr(win, 2 + i, 4, m, attr)
        win.refresh()
        win.move(1, 4 + len(buf))
        k = win.getch()
        if k in (27,):
            curses.curs_set(0); return ""
        elif k in (curses.KEY_ENTER, 10, 13):
            curses.curs_set(0)
            # if the buffer exactly matches nothing but a suggestion is highlighted, take it
            if buf == "" and matches:
                return matches[sugg_idx]
            return buf.strip() if buf.strip() else (matches[sugg_idx] if matches else "")
        elif k in (9,):  # Tab -> complete to highlighted suggestion
            if matches:
                buf = matches[sugg_idx]
        elif k in (curses.KEY_DOWN,):
            sugg_idx = (sugg_idx + 1) % max(1, len(matches))
        elif k in (curses.KEY_UP,):
            sugg_idx = (sugg_idx - 1) % max(1, len(matches))
        elif k in (curses.KEY_BACKSPACE, 127, 8):
            buf = buf[:-1]; sugg_idx = 0
        elif 32 <= k < 127:
            buf += chr(k); sugg_idx = 0


def aggregate_txns(rows, ti, tyi, iti, ami, toi):
    """Collapse consecutive rows with the same (event_type, item_key).
    Indices: ti=time, tyi=type, iti=item, ami=amount(or None), toi=total.
    Returns list of dicts: {last, type, item, count, qty, total}."""
    out = []
    for r in rows:
        if len(r) <= max(ti, tyi, iti, toi) or r[0] == "ERROR":
            continue
        try:
            amt = int(float(r[ami])) if ami is not None and len(r) > ami else 1
        except Exception:
            amt = 1
        try:
            tot = float(r[toi])
        except Exception:
            tot = 0.0
        key = (r[tyi], r[iti])
        if out and out[-1]["key"] == key:
            g = out[-1]
            g["count"] += 1
            g["qty"] += amt
            g["total"] += tot
        else:
            out.append({"key": key, "last": r[ti], "type": r[tyi], "item": r[iti],
                        "count": 1, "qty": amt, "total": tot})
    return out


def fmt_txn(g, namecol=None):
    qty = f"x{g['qty']}" if g["qty"] > 1 else ("x" + str(g["qty"]) if g["count"] > 1 else "")
    extra = f"  (×{g['count']} txns)" if g["count"] > 1 else ""
    name = f"{namecol:<16} " if namecol else ""
    return f"{g['last']:<19} {name}{g['type']:<13} {g['item'][:20]:<20} {qty:<7} ${g['total']:>12,.0f}{extra}"


# ---- Modules ----------------------------------------------------------------

def build_dashboard():
    pid = server_pid()
    sess = find_screen_session()
    lines = []
    lines.append(f"Server process : {'RUNNING (pid ' + pid + ')' if pid else 'DOWN'}")
    lines.append(f"Console screen : {sess or 'NOT FOUND'}")
    lines.append("")
    n_players = db_scalar('SELECT COUNT(*) FROM players')
    n_active = db_scalar('SELECT COUNT(*) FROM punishments WHERE active=1')
    n_bans = db_scalar("SELECT COUNT(*) FROM punishments WHERE active=1 AND type='BAN'")
    n_mutes = db_scalar("SELECT COUNT(*) FROM punishments WHERE active=1 AND type='MUTE'")
    n_ac = db_scalar('SELECT COUNT(*) FROM anticheat_violations WHERE reported_at > NOW() - INTERVAL 1 DAY')
    n_bounty = db_scalar("SELECT COUNT(*) FROM bounties WHERE status='ACTIVE'")
    lines.append(f"Registered players   : {n_players}")
    lines.append(f"Active punishments   : {n_active}   (bans {n_bans} · mutes {n_mutes})")
    lines.append(f"Anticheat flags (24h): {n_ac}")
    lines.append(f"Open bounties        : {n_bounty}")
    lines.append("")
    lines.append("── Top 10 balances ──")
    for r in db_query("SELECT p.username, b.money, b.shards FROM balances b JOIN players p ON p.uuid=b.uuid ORDER BY b.money DESC LIMIT 10"):
        if len(r) >= 3 and r[0] != "ERROR":
            lines.append(f"  {r[0]:<18} ${float(r[1]):>16,.0f}   {r[2]} shards")
    lines.append("")
    lines.append("── Recent transactions (aggregated) ──")
    raw = db_query("SELECT logged_at, player_name, event_type, item_key, amount, total_price FROM transaction_audit_log ORDER BY id DESC LIMIT 80")
    groups = []
    for r in raw:
        if len(r) < 6 or r[0] == "ERROR":
            continue
        key = (r[1], r[2], r[3])
        try:
            amt = int(float(r[4]))
        except Exception:
            amt = 1
        try:
            tot = float(r[5])
        except Exception:
            tot = 0.0
        if groups and groups[-1]["k"] == key:
            groups[-1]["count"] += 1; groups[-1]["qty"] += amt; groups[-1]["total"] += tot
        else:
            groups.append({"k": key, "last": r[0], "name": r[1], "type": r[2], "item": r[3], "count": 1, "qty": amt, "total": tot})
    for g in groups[:18]:
        qty = f"x{g['qty']}" if g["qty"] > 1 else ""
        extra = f" (x{g['count']})" if g["count"] > 1 else ""
        lines.append(f"  {g['last']:<19} {g['name']:<15} {g['type']:<12} {g['item'][:16]:<16} {qty:<6} ${g['total']:>11,.0f}{extra}")
    return "Dashboard", lines


def player_profile_lines(name):
    safe = esc(name)
    base = db_query(f"SELECT uuid, username, playtime_seconds, first_joined, last_seen FROM players WHERE LOWER(username)=LOWER('{safe}') LIMIT 1")
    if not base or len(base[0]) < 5 or base[0][0] == "ERROR":
        return [f"No player found: {name}"], None
    uuid, uname, pt, first, last = base[0][0], base[0][1], base[0][2], base[0][3], base[0][4]
    L = [f"Player : {uname}", f"UUID   : {uuid}",
         f"Played : {int(pt)//3600}h {int(pt)%3600//60}m   First: {first}   Last: {last}"]
    bal = db_query(f"SELECT money, shards FROM balances WHERE uuid='{uuid}'")
    if bal and len(bal[0]) >= 2 and bal[0][0] != "ERROR":
        L.append(f"Balance: ${float(bal[0][0]):,.0f}   Shards: {bal[0][1]}")
    deaths = db_scalar(f"SELECT deaths FROM player_stats WHERE uuid='{uuid}'", "0")
    kills = db_scalar(f"SELECT kills FROM player_stats WHERE uuid='{uuid}'", "0")
    earned = db_scalar(f"SELECT sell_earned FROM player_stats WHERE uuid='{uuid}'", "0")
    L.append(f"Kills: {kills}   Deaths: {deaths}   Lifetime sell_earned: ${float(earned or 0):,.0f}")
    L.append("")
    L.append("── Sell / earn history (top items) ──")
    for r in db_query(f"SELECT item_key, SUM(amount), ROUND(SUM(total_price)) FROM transaction_audit_log WHERE player_uuid='{uuid}' AND event_type LIKE 'SELL%' GROUP BY item_key ORDER BY 3 DESC LIMIT 12"):
        if len(r) >= 3 and r[0] != "ERROR":
            L.append(f"  {r[0][:24]:<24} x{r[1]:<8} ${float(r[2]):>14,.0f}")
    L.append("")
    L.append("── Recent transactions ──")
    raw = db_query(f"SELECT logged_at, event_type, item_key, amount, total_price FROM transaction_audit_log WHERE player_uuid='{uuid}' ORDER BY id DESC LIMIT 120")
    for g in aggregate_txns(raw, 0, 1, 2, 3, 4)[:20]:
        L.append("  " + fmt_txn(g))
    L.append("")
    L.append("── Anticheat flags ──")
    flags = db_query(f"SELECT reported_at, check_name, vl_points FROM anticheat_violations WHERE player_uuid='{uuid}' ORDER BY id DESC LIMIT 10")
    if not flags or flags[0][0] == "ERROR" or not flags[0][0]:
        L.append("  (none)")
    else:
        for r in flags:
            if len(r) >= 3:
                L.append(f"  {r[0]:<19} {r[1]:<28} vl={r[2]}")
    L.append("")
    L.append("── Punishment history ──")
    for r in db_query(f"SELECT type, reason, issued_at, active FROM punishments WHERE target_uuid='{uuid}' ORDER BY id DESC LIMIT 10"):
        if len(r) >= 4 and r[0] != "ERROR":
            state = "ACTIVE" if r[3] == "1" else "past"
            L.append(f"  [{state:<6}] {r[0]:<5} {r[2]:<19} {r[1][:40]}")
    return L, uname


def build_anticheat():
    rows = db_query("SELECT reported_at, player_name, check_name, severity, vl_points, server_name FROM anticheat_violations ORDER BY id DESC LIMIT 300")
    lines = [f"{'WHEN':<19} {'PLAYER':<16} {'CHECK':<26} {'SEV':<7} {'VL':<6} SERVER", "-" * 88]
    if not rows or rows[0][0] == "ERROR" or not rows[0][0]:
        lines.append("(no anticheat flags recorded)")
    else:
        for r in rows:
            if len(r) >= 6:
                lines.append(f"{r[0]:<19} {r[1]:<16} {r[2][:26]:<26} {r[3]:<7} {r[4]:<6} {r[5]}")
    return "Anticheat flags (recent 300)", lines


def build_audit(name):
    lines = []
    if name:
        safe = esc(name)
        uuid = db_scalar(f"SELECT uuid FROM players WHERE LOWER(username)=LOWER('{safe}') LIMIT 1", None)
        if not uuid:
            return f"Audit · {name}", [f"No player: {name}"]
        summary = db_query(f"SELECT event_type, COUNT(*), ROUND(SUM(total_price)) FROM transaction_audit_log WHERE player_uuid='{uuid}' GROUP BY event_type ORDER BY 3 DESC")
        lines.append("── Totals by type ──")
        for r in summary:
            if len(r) >= 3 and r[0] != "ERROR":
                lines.append(f"  {r[0]:<14} {r[1]:>6} events   ${float(r[2] or 0):>16,.0f}")
        lines.append("")
        lines.append(f"{'WHEN':<19} {'TYPE':<13} {'ITEM':<20} {'QTY':<7} {'$':<14}")
        lines.append("-" * 78)
        rows = db_query(f"SELECT logged_at, event_type, item_key, amount, total_price FROM transaction_audit_log WHERE player_uuid='{uuid}' ORDER BY id DESC LIMIT 800")
        for g in aggregate_txns(rows, 0, 1, 2, 3, 4):
            lines.append(fmt_txn(g))
        return f"Audit · {name}", lines
    rows = db_query("SELECT logged_at, player_name, event_type, item_key, amount, total_price FROM transaction_audit_log ORDER BY id DESC LIMIT 800")
    lines.append(f"{'WHEN':<19} {'PLAYER':<15} {'TYPE':<13} {'ITEM':<18} {'QTY':<7} {'$':<14}")
    lines.append("-" * 92)
    groups = []
    for r in rows:
        if len(r) < 6 or r[0] == "ERROR":
            continue
        key = (r[1], r[2], r[3])
        try:
            amt = int(float(r[4]))
        except Exception:
            amt = 1
        try:
            tot = float(r[5])
        except Exception:
            tot = 0.0
        if groups and groups[-1]["k"] == key:
            groups[-1]["count"] += 1; groups[-1]["qty"] += amt; groups[-1]["total"] += tot
        else:
            groups.append({"k": key, "last": r[0], "name": r[1], "type": r[2], "item": r[3], "count": 1, "qty": amt, "total": tot})
    for g in groups:
        qty = f"x{g['qty']}" if g["qty"] > 1 else ""
        extra = f" (x{g['count']})" if g["count"] > 1 else ""
        lines.append(f"{g['last']:<19} {g['name']:<15} {g['type']:<13} {g['item'][:18]:<18} {qty:<7} ${g['total']:>12,.0f}{extra}")
    return "Audit · all (recent 800, aggregated)", lines


def build_logs(query):
    log = os.path.join(SERVER_DIR, "logs", "latest.log")
    try:
        with open(log, "r", errors="ignore") as f:
            data = f.read().splitlines()
    except Exception as e:
        return "Logs", [f"Cannot read log: {e}"]
    if query:
        ql = query.lower()
        lines = [ln for ln in data if ql in ln.lower()][-800:]
        return f"latest.log · filter '{query}' ({len(lines)})", (lines or ["(no matching lines)"])
    return "latest.log · last 500", data[-500:]


def build_online():
    send_console("list")
    curses.napms(450)
    log = os.path.join(SERVER_DIR, "logs", "latest.log")
    lines = []
    try:
        with open(log, "r", errors="ignore") as f:
            tail = f.read().splitlines()[-40:]
        idx = None
        for i, ln in enumerate(tail):
            if "players online" in ln.lower():
                idx = i
        if idx is not None:
            lines.append(tail[idx].split("]: ")[-1] if "]: " in tail[idx] else tail[idx])
            if idx + 1 < len(tail):
                names = tail[idx + 1].split("]: ")[-1] if "]: " in tail[idx + 1] else tail[idx + 1]
                lines.append("Online: " + names)
        else:
            lines.append("Could not parse online list — check console.")
    except Exception as e:
        lines.append(f"Error: {e}")
    lines.append("")
    lines.append("── Recently seen (DB) ──")
    for r in db_query("SELECT username, last_seen FROM players ORDER BY last_seen DESC LIMIT 20"):
        if len(r) >= 2 and r[0] != "ERROR":
            lines.append(f"  {r[0]:<18} {r[1]}")
    return "Online players", lines


# ---- UI framework (persistent sidebar) -------------------------------------
SIDEBAR_W = 20


def draw_frame(stdscr, sel, focus_content):
    stdscr.erase()
    h, w = stdscr.getmaxyx()
    stdscr.attron(curses.color_pair(4) | curses.A_BOLD)
    safe_addstr(stdscr, 0, 0, " " * (w - 1))
    safe_addstr(stdscr, 0, 2, "ExampleSMP Admin Console")
    pid = server_pid()
    st = "● RUNNING" if pid else "○ DOWN"
    safe_addstr(stdscr, 0, w - len(st) - 2, st)
    stdscr.attroff(curses.color_pair(4) | curses.A_BOLD)
    for i, sec in enumerate(SECTIONS):
        y = 2 + i
        if i == sel:
            attr = curses.A_REVERSE | curses.A_BOLD
            safe_addstr(stdscr, y, 0, f" > {sec['name']:<{SIDEBAR_W-3}}", attr)
        else:
            safe_addstr(stdscr, y, 0, f"   {sec['name']:<{SIDEBAR_W-3}}", curses.A_DIM if focus_content else curses.A_NORMAL)
    for y in range(2, h - 1):
        safe_addstr(stdscr, y, SIDEBAR_W, "│", curses.A_DIM)
    return (2, SIDEBAR_W + 2, h - 3, w - SIDEBAR_W - 3)


def render_content(stdscr, sel, title, lines, scroll, footer):
    cy, cx, ch, cw = draw_frame(stdscr, sel, True)
    safe_addstr(stdscr, cy, cx, title[:cw], curses.color_pair(3) | curses.A_BOLD)
    safe_addstr(stdscr, cy + 1, cx, "─" * min(cw, max(10, len(title))), curses.A_DIM)
    view = lines[scroll:scroll + ch - 2]
    for i, ln in enumerate(view):
        safe_addstr(stdscr, cy + 2 + i, cx, ln[:cw])
    if len(lines) > ch - 2:
        safe_addstr(stdscr, cy, cx + cw - 9, f"{scroll+1}/{len(lines)}", curses.A_DIM)
    h, w = stdscr.getmaxyx()
    safe_addstr(stdscr, h - 1, 2, footer, curses.A_DIM)
    stdscr.refresh()


def content_view(stdscr, sel, builder, footer="↑/↓ PgUp/PgDn scroll · r refresh · ←/Esc back"):
    scroll = 0
    title, lines = builder()
    while True:
        render_content(stdscr, sel, title, lines, scroll, footer)
        k = stdscr.getch()
        h, _ = stdscr.getmaxyx()
        page = max(1, h - 7)
        if k in (27, curses.KEY_LEFT, ord("q")):
            return
        elif k in (curses.KEY_DOWN, ord("j")):
            scroll = min(max(0, len(lines) - 1), scroll + 1)
        elif k in (curses.KEY_UP, ord("k")):
            scroll = max(0, scroll - 1)
        elif k == curses.KEY_NPAGE:
            scroll = min(max(0, len(lines) - 1), scroll + page)
        elif k == curses.KEY_PPAGE:
            scroll = max(0, scroll - page)
        elif k in (ord("r"), ord("R")):
            title, lines = builder()
            scroll = min(scroll, max(0, len(lines) - 1))


def flash(stdscr, msg, ok=True):
    h, w = stdscr.getmaxyx()
    attr = curses.A_BOLD | (curses.color_pair(1) if ok else curses.color_pair(2))
    safe_addstr(stdscr, h - 1, 2, (" " + msg)[: w - 4].ljust(w - 4), attr)
    stdscr.refresh()
    curses.napms(1100)


# ---- Section run handlers ---------------------------------------------------
def run_dashboard(stdscr, sel):
    content_view(stdscr, sel, build_dashboard)


def run_players(stdscr, sel):
    name = prompt_player(stdscr, "Player to look up")
    if not name:
        return
    content_view(stdscr, sel, lambda: ("Player · " + name, player_profile_lines(name)[0]))


def run_anticheat(stdscr, sel):
    content_view(stdscr, sel, build_anticheat)


def run_audit(stdscr, sel):
    name = prompt_player(stdscr, "Audit player (blank+Enter = all)")
    content_view(stdscr, sel, lambda: build_audit(name))


def run_online(stdscr, sel):
    content_view(stdscr, sel, build_online)


def run_logs(stdscr, sel):
    q = prompt(stdscr, "Filter logs by text (blank = last 500)")
    content_view(stdscr, sel, lambda: build_logs(q))


def run_economy(stdscr, sel):
    name = prompt_player(stdscr, "Economy: player")
    if not name:
        return
    while True:
        safe = esc(name)
        uuid = db_scalar(f"SELECT uuid FROM players WHERE LOWER(username)=LOWER('{safe}') LIMIT 1", None)
        lines = []
        if uuid:
            bal = db_query(f"SELECT money, shards, pending_vault_credit FROM balances WHERE uuid='{uuid}'")
            if bal and len(bal[0]) >= 3 and bal[0][0] != "ERROR":
                lines.append(f"Money: ${float(bal[0][0]):,.2f}   Shards: {bal[0][1]}   Pending: ${float(bal[0][2]):,.2f}")
        lines += ["", "[g] Give money", "[t] Take money", "[s] Set money",
                  "[h] Give shards", "[v] View full profile"]
        render_content(stdscr, sel, f"Economy · {name}", lines, 0, "g/t/s/h/v · ←/Esc back")
        k = stdscr.getch()
        if k in (27, curses.KEY_LEFT, ord("q")):
            return
        elif k == ord("g"):
            a = prompt(stdscr, f"Give money to {name}")
            if a:
                ok, m = send_console(f"eco give {name} {a}"); flash(stdscr, m, ok)
        elif k == ord("t"):
            a = prompt(stdscr, f"Take money from {name}")
            if a:
                ok, m = send_console(f"eco take {name} {a}"); flash(stdscr, m, ok)
        elif k == ord("s"):
            a = prompt(stdscr, f"Set {name}'s money")
            if a:
                ok, m = send_console(f"eco set {name} {a}"); flash(stdscr, m, ok)
        elif k == ord("h"):
            a = prompt(stdscr, f"Give shards to {name} (k/m/b/t)")
            if a:
                ok, m = send_console(f"shards give {name} {a}"); flash(stdscr, m, ok)
        elif k == ord("v"):
            content_view(stdscr, sel, lambda: ("Player · " + name, player_profile_lines(name)[0]))


def run_punishments(stdscr, sel):
    while True:
        rows = db_query("SELECT id, type, target_name, actor_name, reason, expires_at FROM punishments WHERE active=1 ORDER BY id DESC LIMIT 50")
        lines = [f"{'ID':<8}{'TYPE':<6}{'TARGET':<17}{'BY':<13}{'EXPIRES':<21}REASON", "-" * 80]
        for r in rows:
            if len(r) >= 6 and r[0] != "ERROR":
                lines.append(f"{r[0]:<8}{r[1]:<6}{r[2]:<17}{r[3]:<13}{r[5]:<21}{r[4][:24]}")
        render_content(stdscr, sel, "Active punishments", lines, 0, "[b]an [m]ute [k]ick [u]nban/unmute · ←/Esc back")
        k = stdscr.getch()
        if k in (27, curses.KEY_LEFT, ord("q")):
            return
        elif k == ord("b"):
            t = prompt(stdscr, "Ban: <player> <7d|perm> <reason>")
            if t:
                flash(stdscr, *reversed(send_console("ban " + t)))
        elif k == ord("m"):
            t = prompt(stdscr, "Mute: <player> <duration> <reason>")
            if t:
                flash(stdscr, *reversed(send_console("mute " + t)))
        elif k == ord("k"):
            t = prompt(stdscr, "Kick: <player> <reason>")
            if t:
                flash(stdscr, *reversed(send_console("kick " + t)))
        elif k == ord("u"):
            t = prompt_player(stdscr, "Unban+unmute player")
            if t:
                send_console("unban " + t); send_console("unmute " + t)
                flash(stdscr, f"Issued unban+unmute for {t}", True)


def run_maintenance(stdscr, sel):
    while True:
        lines = ["── FROZEN maintenance (custom, recommended) ──",
                 "Players stay connected, see their last spot, can't move,",
                 "and are auto-unfrozen when you end it. Staff stay free.",
                 "",
                 "[F] Start FROZEN maintenance (with reason)",
                 "[E] End FROZEN maintenance",
                 "[T] Frozen status",
                 "",
                 "── KICK maintenance (kennytv plugin) ──",
                 "[o] Maintenance ON (kicks non-staff)",
                 "[f] Maintenance OFF   [s] Status   [m] Set MOTD",
                 "[w] Allow player during kick-maintenance",
                 "",
                 "Future: MIRROR live-update (2nd server, same worlds) — deferred."]
        render_content(stdscr, sel, "Maintenance", lines, 0, "F/E/T frozen · o/f/s/m/w kick · ←/Esc back")
        k = stdscr.getch()
        if k in (27, curses.KEY_LEFT, ord("q")):
            return
        elif k == ord("F"):
            reason = prompt(stdscr, "Maintenance reason (blank = default)")
            cmd = "servermaint start" + (f" {reason}" if reason else "")
            if confirm(stdscr, "Freeze all non-staff players now?"):
                flash(stdscr, *reversed(send_console(cmd)))
        elif k == ord("E"):
            flash(stdscr, *reversed(send_console("servermaint end")))
        elif k == ord("T"):
            send_console("servermaint status"); flash(stdscr, "Frozen status sent to console.", True)
        elif k == ord("o"):
            if confirm(stdscr, "Enable KICK maintenance?"):
                flash(stdscr, *reversed(send_console("maintenance on")))
        elif k == ord("f"):
            flash(stdscr, *reversed(send_console("maintenance off")))
        elif k == ord("s"):
            send_console("maintenance status"); flash(stdscr, "Status sent to console.", True)
        elif k == ord("m"):
            t = prompt(stdscr, "Maintenance MOTD text")
            if t:
                # Our PNC command (the kennytv 'maintenance motd' only LISTS).
                flash(stdscr, *reversed(send_console(f"maintenancemotd {t}")))
        elif k == ord("w"):
            t = prompt_player(stdscr, "Allow player during maintenance")
            if t:
                flash(stdscr, *reversed(send_console(f"maintenance whitelist add {t}")))


def run_server(stdscr, sel):
    while True:
        pid = server_pid()
        lines = [f"Status: {'RUNNING (pid ' + pid + ')' if pid else 'DOWN'}", "",
                 "[a] Broadcast (say)", "[s] Save-all", "[w] Whitelist add/remove",
                 "[c] Raw console command", "[R] Restart (save + stop)",
                 "",
                 "── scripts/ ops ──",
                 "[S] status.sh   [D] db_status.sh   [B] create_backup.sh",
                 "[M] reload_menus.sh   [K] smoke_test.sh"]
        render_content(stdscr, sel, "Server admin", lines, 0, "a/s/w/c/R · S/D/B/M/K scripts · ←/Esc back")
        k = stdscr.getch()
        if k in (27, curses.KEY_LEFT, ord("q")):
            return
        elif k == ord("a"):
            t = prompt(stdscr, "Broadcast message")
            if t:
                flash(stdscr, *reversed(send_console("say " + t)))
        elif k == ord("s"):
            flash(stdscr, *reversed(send_console("save-all")))
        elif k == ord("w"):
            t = prompt(stdscr, "whitelist <add|remove> <player>")
            if t:
                flash(stdscr, *reversed(send_console("whitelist " + t)))
        elif k == ord("c"):
            t = prompt(stdscr, "Raw console command")
            if t:
                flash(stdscr, *reversed(send_console(t)))
        elif k == ord("R"):
            if confirm(stdscr, "Restart the server?"):
                send_console("say §eServer restarting shortly…")
                send_console("save-all"); send_console("stop")
                flash(stdscr, "Save+stop issued. scripts/restart.sh will bring it back.", True)
        elif k == ord("S"):
            flash(stdscr, *reversed(run_script("status.sh")))
        elif k == ord("D"):
            flash(stdscr, *reversed(run_script("db_status.sh")))
        elif k == ord("B"):
            if confirm(stdscr, "Run a full backup now?"):
                flash(stdscr, *reversed(run_script("create_backup.sh", timeout=600)))
        elif k == ord("M"):
            flash(stdscr, *reversed(run_script("reload_menus.sh")))
        elif k == ord("K"):
            flash(stdscr, *reversed(run_script("smoke_test.sh")))


SECTIONS = [
    {"name": "Dashboard",   "desc": "Live server status, active bans/mutes, 24h anticheat flag count, open bounties, top balances and recent (aggregated) transactions.", "run": run_dashboard},
    {"name": "Players",     "desc": "Look up any player by name (with autocomplete) → balance, shards, K/D, lifetime sell_earned, sell history, recent transactions, anticheat flags and punishment history.", "run": run_players},
    {"name": "Economy",     "desc": "Give / take / set a player's money and give shards (k/m/b/t). Shows the current balance and links to the full profile.", "run": run_economy},
    {"name": "Punishments", "desc": "View all active bans/mutes and issue ban / mute / kick, or unban+unmute, straight to the server console.", "run": run_punishments},
    {"name": "Anticheat",   "desc": "Recent GrimAC flags (player, check, severity, VL points, server). These are hidden from in-game staff chat — review them here.", "run": run_anticheat},
    {"name": "Audit",       "desc": "Transaction audit log. Pick a player for per-type totals + history, or leave blank for the recent server-wide feed. Identical events are collapsed (x12).", "run": run_audit},
    {"name": "Online",      "desc": "Query the live online player list from the console, plus recently-seen players from the database.", "run": run_online},
    {"name": "Logs",        "desc": "Read latest.log — last 500 lines, or filter by any text (a player name, 'ERROR', a command). Press r to refresh live.", "run": run_logs},
    {"name": "Maintenance", "desc": "FROZEN maintenance (custom): non-staff stay connected, see their last spot, can't move/interact, auto-released when you end it — for safe live updates. Plus the kennytv KICK maintenance (on/off/MOTD/whitelist).", "run": run_maintenance},
    {"name": "Server",      "desc": "Broadcast, save-all, whitelist, run any raw console command, or restart the server.", "run": run_server},
    {"name": "Quit",        "desc": "Exit the admin console.", "run": None},
]


def main(stdscr):
    curses.curs_set(0)
    curses.start_color()
    curses.use_default_colors()
    curses.init_pair(1, curses.COLOR_GREEN, -1)
    curses.init_pair(2, curses.COLOR_RED, -1)
    curses.init_pair(3, curses.COLOR_CYAN, -1)
    curses.init_pair(4, curses.COLOR_WHITE, curses.COLOR_BLUE)
    sel = 0
    while True:
        cy, cx, ch, cw = draw_frame(stdscr, sel, False)
        sec = SECTIONS[sel]
        safe_addstr(stdscr, cy, cx, sec["name"], curses.color_pair(3) | curses.A_BOLD)
        safe_addstr(stdscr, cy + 1, cx, "─" * min(cw, 40), curses.A_DIM)
        wrapped = textwrap.wrap(sec["desc"], max(20, cw - 1))
        for i, dl in enumerate(wrapped):
            safe_addstr(stdscr, cy + 3 + i, cx, dl)
        if sec["run"]:
            safe_addstr(stdscr, cy + 4 + len(wrapped), cx, "Press Enter or → to open.", curses.A_DIM | curses.color_pair(1))
        h, w = stdscr.getmaxyx()
        safe_addstr(stdscr, h - 1, 2, "↑/↓ choose section · Enter/→ open · q quit", curses.A_DIM)
        stdscr.refresh()
        k = stdscr.getch()
        if k in (curses.KEY_DOWN, ord("j")):
            sel = (sel + 1) % len(SECTIONS)
        elif k in (curses.KEY_UP, ord("k")):
            sel = (sel - 1) % len(SECTIONS)
        elif k in (ord("q"), 27):
            return
        elif k in (curses.KEY_ENTER, 10, 13, curses.KEY_RIGHT):
            if SECTIONS[sel]["name"] == "Quit":
                return
            SECTIONS[sel]["run"](stdscr, sel)


if __name__ == "__main__":
    if "--check" in sys.argv:
        print("DB players:", db_scalar("SELECT COUNT(*) FROM players"))
        print("screen:", find_screen_session())
        print("paper pid:", server_pid())
        sys.exit(0)
    try:
        curses.wrapper(main)
    except KeyboardInterrupt:
        pass
