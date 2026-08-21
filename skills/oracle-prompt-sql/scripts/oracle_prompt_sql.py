#!/usr/bin/env python3
"""Generate and verify single-statement Oracle INSERTs for KYC_DATA_OWNER.AI_CHAT_PROMPT.

    generate:  python3 oracle_prompt_sql.py gen <prompt-dir> --code <CW_PROMPT_CODE> [-o FILE]
    verify:    python3 oracle_prompt_sql.py verify <sql-file> <prompt-dir>

`gen` verifies its own output before writing, so a bad statement is never produced.
`verify` works on any SQL file, including one written by hand or by a model — it
DECODES the literals back and compares them to the source files, which is the only
check that catches a dropped character inside a 110 KB literal.

The target is a Java PreparedStatement handed the whole query as one string. See
references/why-these-constraints.md for why each rule below exists; every one of
them was learned by breaking production.
"""

import argparse
import json
import os
import re
import sys

TABLE = "KYC_DATA_OWNER.AI_CHAT_PROMPT"

# Max UTF-8 bytes for the VALUE of one TO_CLOB() piece. Oracle's hard cap is 4000;
# the headroom absorbs a multi-byte character landing on a chunk boundary.
MAX_PIECE_BYTES = 3000
ORACLE_LITERAL_CAP = 4000

# real character -> (sentinel standing in for it, its CHR() code)
# Sentinels must be ABSENT from every source file; encode() asserts that.
SENTINELS = [
    ("\n", "~", 10),
    ("&", "^", 38),
    (";", "!", 59),
    (":", "*", 58),
    ("?", "@", 63),
]
FROM_STR = "".join(s for _, s, _ in SENTINELS)
TO_EXPR = "||".join("CHR(%d)" % c for _, _, c in SENTINELS)
DECODE = {s: r for r, s, _ in SENTINELS}

# Consumed by the client before Oracle sees them. None may survive anywhere.
FORBIDDEN = "&;:?"

# CLOB column -> source filename. SELECT-list order must match this order.
CLOB_COLUMNS = [
    ("CW_PROMPT_TEXT", "user.txt"),
    ("SYSTEM_INSTRUCTION", "system.txt"),
    ("CW_RESPONSE_SCHEMA", "schema.json"),
]

# Inherited verbatim from the previous version — never retyped.
INHERITED = [
    "CW_PROMPT_LANG", "CW_MODEL_NAME", "CW_MAX_OUTPUT_TOKENS",
    "CW_CALL_TIMEOUT_MILLIS", "CW_TEMPERATURE", "CW_TOP_P", "CW_TOP_K",
    "CW_THINKING_BUDGET", "CW_SEED", "CRITIC_LOOPS", "CW_LOCATION",
]

# NOT NULL in the DDL. DEFAULT_PROMPT_VERSION is unused and NOT NULL DEFAULT 'Y',
# so the table default satisfies it; it is deliberately not named in the insert.
NOT_NULL = ["CW_PROMPT_CODE", "PROMPT_VERSION", "CREATED_BY", "CREATE_DATE",
            "LAST_UPDATED_BY", "LAST_UPDATE_DATE"]
HAS_TABLE_DEFAULT = {"PROMPT_VERSION", "DEFAULT_PROMPT_VERSION"}

PIECE_RE = re.compile(
    r"TO_CLOB\(\s*TRANSLATE\(\s*'((?:[^']|'')*)'\s*,\s*'((?:[^']|'')*)'\s*,"
    r"\s*((?:CHR\(\d+\)\s*\|\|\s*)*CHR\(\d+\))\s*\)\s*\)")


# --------------------------------------------------------------------------- generate

def encode(text, where):
    for real, sentinel, _ in SENTINELS:
        if sentinel in text:
            raise SystemExit(
                "%s contains %r, which is reserved as the sentinel for %r.\n"
                "Pick a different sentinel in SENTINELS rather than weakening this check "
                "— a sentinel that also occurs naturally silently corrupts the stored text."
                % (where, sentinel, real))
    for real, sentinel, _ in SENTINELS:
        text = text.replace(real, sentinel)
    return text


def chunk(text):
    """Split so every piece's VALUE stays under MAX_PIECE_BYTES."""
    out, buf, n = [], [], 0
    for ch in text:
        w = len(ch.encode("utf-8"))
        if n + w > MAX_PIECE_BYTES and buf:
            out.append("".join(buf))
            buf, n = [], 0
        buf.append(ch)
        n += w
    if buf:
        out.append("".join(buf))
    return out


def clob_pieces(text, where, indent="    "):
    return ["%sTO_CLOB(TRANSLATE('%s','%s',%s))"
            % (indent, c.replace("'", "''"), FROM_STR, TO_EXPR)
            for c in chunk(encode(text, where))]


def read_sources(prompt_dir):
    payload = {}
    for column, fname in CLOB_COLUMNS:
        path = os.path.join(prompt_dir, fname)
        if not os.path.exists(path):
            raise SystemExit("missing source file: %s" % path)
        with open(path, encoding="utf-8") as fh:
            payload[column] = fh.read()
    try:
        json.loads(payload["CW_RESPONSE_SCHEMA"])
    except ValueError as exc:
        raise SystemExit("schema.json is not valid JSON, refusing to store it: %s" % exc)
    return payload


def build(prompt_dir, code):
    payload = read_sources(prompt_dir)
    q = "'" + code + "'"
    L, stats = [], []
    a = L.append
    a("INSERT INTO %s (" % TABLE)
    a("  CW_PROMPT_CODE, PROMPT_VERSION,")
    a("  " + ", ".join(INHERITED[:3]) + ",")
    a("  " + ", ".join(INHERITED[3:7]) + ",")
    a("  " + ", ".join(INHERITED[7:]) + ",")
    a("  " + ", ".join(c for c, _ in CLOB_COLUMNS) + ",")
    a("  CREATED_BY, CREATE_DATE, LAST_UPDATED_BY, LAST_UPDATE_DATE")
    a(")")
    a("SELECT")
    a("  P.CW_PROMPT_CODE,")
    a("  P.PROMPT_VERSION + 1,")
    for c in INHERITED:
        a("  P.%s," % c)
    for column, fname in CLOB_COLUMNS:
        pieces = clob_pieces(payload[column], "%s/%s" % (prompt_dir, fname))
        stats.append((column, fname, len(payload[column]), len(pieces)))
        a("  (")
        for i, p in enumerate(pieces):
            a(p + ("" if i == len(pieces) - 1 else "||"))
        a("  ),")
    a("  USER,")
    a("  SYSTIMESTAMP,")
    a("  USER,")
    a("  SYSTIMESTAMP")
    a("FROM %s P" % TABLE)
    a("WHERE P.CW_PROMPT_CODE = %s" % q)
    a("  AND P.PROMPT_VERSION = (SELECT MAX(PROMPT_VERSION) FROM %s WHERE CW_PROMPT_CODE = %s)"
      % (TABLE, q))
    return "\n".join(L) + "\n", stats


# ----------------------------------------------------------------------------- verify

def strip_literals(sql):
    """Blank out every '...' literal. Counting commas or hunting for ';' without
    doing this is the classic way to get a wrong answer — the prompt body is full
    of commas, parens and semicolons that mean nothing to the parser."""
    out, i, n = [], 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch == "'":
            i += 1
            while i < n:
                if sql[i] == "'":
                    if i + 1 < n and sql[i + 1] == "'":
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append("''")
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def split_top_level(text):
    """Split on commas at paren depth 0."""
    items, depth, buf = [], 0, []
    for ch in text:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            items.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    if "".join(buf).strip():
        items.append("".join(buf))
    return items


def decode_groups(sql):
    """Recover (decoded_text, [piece_byte_lengths]) per CLOB column.

    Consecutive TO_CLOB pieces separated by nothing but '||' belong to the same
    column; anything else between them starts a new one."""
    matches = list(PIECE_RE.finditer(sql))
    groups, cur, sizes, cur_sizes = [], [], [], []
    for idx, m in enumerate(matches):
        literal, from_str, to_expr = m.group(1), m.group(2), m.group(3)
        chr_codes = [int(c) for c in re.findall(r"CHR\((\d+)\)", to_expr)]
        if len(from_str) != len(chr_codes):
            raise SystemExit(
                "TRANSLATE from_string is %d chars but to_string is %d — Oracle silently "
                "DELETES the unmatched characters rather than erroring."
                % (len(from_str), len(chr_codes)))
        table = {f: chr(c) for f, c in zip(from_str, chr_codes)}
        value = literal.replace("''", "'")
        cur.append("".join(table.get(ch, ch) for ch in value))
        cur_sizes.append(len(value.encode("utf-8")))
        if idx + 1 < len(matches):
            between = sql[m.end():matches[idx + 1].start()]
            if re.fullmatch(r"\s*\|\|\s*", between):
                continue
        groups.append("".join(cur))
        sizes.append(cur_sizes)
        cur, cur_sizes = [], []
    return groups, sizes


def clob_column_order(bare_sql):
    """The CLOB columns in the order the INSERT names them, so decoded groups are
    matched to the right source file. Falls back to the canonical order when the
    column list cannot be parsed."""
    m = re.search(r"INSERT\s+INTO\s+\S+\s*\((.*?)\)\s*SELECT", bare_sql, re.I | re.S)
    if not m:
        return list(CLOB_COLUMNS)
    named = [c.strip().upper() for c in split_top_level(m.group(1))]
    by_name = dict(CLOB_COLUMNS)
    ordered = [(c, by_name[c]) for c in named if c in by_name]
    return ordered or list(CLOB_COLUMNS)


def verify(sql, prompt_dir, warnings=None):
    """Run every check in DATABASE.md §5.

    Returns failures — things that would store wrong data or stop the statement
    running. Anything appended to `warnings` is safe to run as-is but fragile;
    keeping the two apart matters, because a verifier that cries wolf on a
    survivable issue teaches people to ignore it."""
    fails = []
    warn = warnings if warnings is not None else []
    bare = strip_literals(sql)

    # 2/3. the four characters the client eats, across the WHOLE statement
    for ch in FORBIDDEN:
        n = sql.count(ch)
        if n:
            fails.append("contains %d %r — the client consumes it before Oracle sees it" % (n, ch))
    for m in re.finditer(r":\w", sql):
        fails.append("named-bind pattern %r at offset %d" % (m.group(0), m.start()))
        break

    # 9. one statement, no PL/SQL, no comments — checked outside literals only
    n_insert = len(re.findall(r"\bINSERT\s+INTO\b", bare, re.I))
    if n_insert != 1:
        fails.append("found %d INSERT INTO, expected exactly 1 (PreparedStatement takes one statement)" % n_insert)
    for tok in ("BEGIN", "DECLARE", "END"):
        if re.search(r"\b%s\b" % tok, bare, re.I):
            fails.append("PL/SQL token %r present — a PreparedStatement cannot run an anonymous block" % tok)
    if "--" in bare or "/*" in bare:
        fails.append("comment token present — '--' swallows the rest of a flattened statement")
    if sql.rstrip().endswith(";"):
        fails.append("trailing semicolon — Oracle JDBC rejects it")

    # 5/8. literal hygiene
    n_multiline = sum(1 for m in PIECE_RE.finditer(sql) if "\n" in m.group(1))
    if n_multiline:
        warn.append("%d literal(s) span a line. The statement runs correctly as-is, but a tool "
                    "that reflows or re-wraps the file would change the stored text. Encoding "
                    "newlines as a sentinel (~ -> CHR(10)) removes the hazard." % n_multiline)

    # 1/4. decode back and compare, and measure the real literal VALUES.
    # Which source file a CLOB group belongs to is decided by the INSERT column
    # list, NOT by a fixed order: a statement may name the three CLOB columns in
    # any order, and assuming one order reports a false mismatch on valid SQL.
    groups, sizes = decode_groups(sql)
    order = clob_column_order(bare)
    if len(groups) != len(order):
        fails.append("found %d CLOB groups but the INSERT names %d CLOB columns (%s)"
                     % (len(groups), len(order), ", ".join(c for c, _ in order)))
    else:
        for (column, fname), decoded in zip(order, groups):
            path = os.path.join(prompt_dir, fname)
            try:
                with open(path, encoding="utf-8") as fh:
                    source = fh.read()
            except IOError as exc:
                fails.append("cannot read %s: %s" % (path, exc))
                continue
            if decoded != source:
                where = next((i for i, (a, b) in enumerate(zip(decoded, source)) if a != b),
                             min(len(decoded), len(source)))
                fails.append(
                    "%s does NOT round-trip to %s — first difference at character %d "
                    "(decoded %r vs source %r), decoded %d chars vs source %d"
                    % (column, fname, where, decoded[where:where + 30],
                       source[where:where + 30], len(decoded), len(source)))
    biggest = max((b for g in sizes for b in g), default=0)
    if biggest > ORACLE_LITERAL_CAP:
        fails.append("largest literal value is %d bytes, over Oracle's %d-byte cap "
                     "(the 32767 figure is PL/SQL only)" % (biggest, ORACLE_LITERAL_CAP))

    # 6. column count == select-item count, literals stripped first
    m = re.search(r"INSERT\s+INTO\s+\S+\s*\((.*?)\)\s*SELECT", bare, re.I | re.S)
    if not m:
        fails.append("could not locate the INSERT column list")
    else:
        ncols = len(split_top_level(m.group(1)))
        sel = re.search(r"\bSELECT\b(.*?)\bFROM\b", bare[m.end() - len("SELECT"):], re.I | re.S)
        if not sel:
            fails.append("could not locate the SELECT list")
        else:
            nitems = len(split_top_level(sel.group(1)))
            if ncols != nitems:
                fails.append("INSERT names %d columns but SELECT returns %d items" % (ncols, nitems))
        # 7. NOT NULL coverage
        named = {c.strip().upper() for c in split_top_level(m.group(1))}
        for col in NOT_NULL:
            if col not in named and col not in HAS_TABLE_DEFAULT:
                fails.append("NOT NULL column %s is neither supplied nor table-defaulted" % col)

    # Charset dependency: only & ; : ? are encoded. Everything else — accented
    # letters, dashes, currency and maths symbols — travels as a real character
    # and depends on the connection charset. No SQL-side check can catch a
    # driver-side downgrade, so surface it rather than implying it is covered.
    non_ascii = {}
    for gtext in groups:
        for ch in gtext:
            if ord(ch) > 127:
                non_ascii[ch] = non_ascii.get(ch, 0) + 1
    if non_ascii:
        top = ", ".join("%r x%d" % (c, n) for c, n in
                        sorted(non_ascii.items(), key=lambda kv: -kv[1])[:6])
        warn.append("%d non-ASCII characters (%s) are stored as real characters, NOT encoded. "
                    "They survive only if the connection charset is AL32UTF8; on a single-byte "
                    "charset the driver silently converts them to a question mark. Confirm "
                    "NLS_CHARACTERSET and spot-check DBMS_LOB.GETLENGTH after the insert."
                    % (sum(non_ascii.values()), top))

    # 10. schema.json is valid JSON
    schema_path = os.path.join(prompt_dir, "schema.json")
    if os.path.exists(schema_path):
        try:
            with open(schema_path, encoding="utf-8") as fh:
                json.load(fh)
        except ValueError as exc:
            fails.append("schema.json is not valid JSON: %s" % exc)

    return fails


# ------------------------------------------------------------------------------- cli

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("gen", help="generate the INSERT (verifies before writing)")
    g.add_argument("prompt_dir", help="folder holding system.txt, user.txt, schema.json")
    g.add_argument("--code", required=True, help="CW_PROMPT_CODE, max 50 chars")
    g.add_argument("-o", "--out", help="output path (default <prompt-dir>/insert_prompt.sql)")

    v = sub.add_parser("verify", help="decode a SQL file back and check it against the sources")
    v.add_argument("sql_file")
    v.add_argument("prompt_dir")

    args = ap.parse_args()

    if args.cmd == "gen":
        if len(args.code) > 50:
            raise SystemExit("CW_PROMPT_CODE is %d chars, column is VARCHAR2(50 CHAR)" % len(args.code))
        sql, stats = build(args.prompt_dir, args.code)
        warns = []
        fails = verify(sql, args.prompt_dir, warns)
        if fails:
            sys.stderr.write("REFUSING TO WRITE — generated SQL failed verification:\n")
            for f in fails:
                sys.stderr.write("  - %s\n" % f)
            return 1
        out = args.out or os.path.join(args.prompt_dir, "insert_prompt.sql")
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(sql)
        print("%s  (%s)" % (out, args.code))
        for column, fname, nchars, npieces in stats:
            print("   %-20s %-11s %7d chars  %3d pieces" % (column, fname, nchars, npieces))
        print("   statement: %d bytes, %d lines, verified: round-trips exactly, "
              "no & ; : ?" % (len(sql.encode("utf-8")), sql.count("\n")))
        for w in warns:
            print("   NOTE: %s" % w)
        return 0

    with open(args.sql_file, encoding="utf-8") as fh:
        sql = fh.read()
    warns = []
    fails = verify(sql, args.prompt_dir, warns)
    if fails:
        print("FAIL (%d)" % len(fails))
        for f in fails:
            print("  - %s" % f)
    else:
        print("PASS — every CLOB round-trips byte-identically; no & ; : ? ; one statement; "
              "all literals under %d bytes" % ORACLE_LITERAL_CAP)
    for w in warns:
        print("  NOTE: %s" % w)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
