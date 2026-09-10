/*
 * Generate and verify single-statement Oracle INSERTs for KYC_DATA_OWNER.AI_CHAT_PROMPT.
 *
 * Java port of oracle_prompt_sql.py — same CLI, same checks, byte-identical SQL.
 * No build step: runs directly from source on JDK 11+.
 *
 *     generate:  java OraclePromptSql.java gen <prompt-dir> --code <CW_PROMPT_CODE> [-o FILE]
 *     verify:    java OraclePromptSql.java verify <sql-file> <prompt-dir>
 *
 * `gen` verifies its own output before writing, so a bad statement is never produced.
 * `verify` works on any SQL file, including one written by hand or by a model — it
 * DECODES the literals back and compares them to the source files, which is the only
 * check that catches a dropped character inside a 110 KB literal.
 *
 * Keep in sync with oracle_prompt_sql.py. See references/why-these-constraints.md for
 * why each rule exists.
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OraclePromptSql {

    static final String TABLE = "KYC_DATA_OWNER.AI_CHAT_PROMPT";

    // Max UTF-8 bytes for the VALUE of one TO_CLOB() piece. Oracle's hard cap is 4000;
    // the headroom absorbs a multi-byte character landing on a chunk boundary.
    static final int MAX_PIECE_BYTES = 3000;
    static final int ORACLE_LITERAL_CAP = 4000;

    // real character -> sentinel standing in for it -> its CHR() code.
    // Sentinels must be ABSENT from every source file; encode() asserts that.
    static final String[] REAL = {"\n", "&", ";", ":", "?"};
    static final String[] SENTINEL = {"~", "^", "!", "*", "@"};
    static final int[] CHR_CODE = {10, 38, 59, 58, 63};
    static final String FROM_STR = String.join("", SENTINEL);
    static final String TO_EXPR;
    static {
        List<String> parts = new ArrayList<>();
        for (int c : CHR_CODE) parts.add("CHR(" + c + ")");
        TO_EXPR = String.join("||", parts);
    }

    // Consumed by the client before Oracle sees them. None may survive anywhere.
    static final String FORBIDDEN = "&;:?";

    // CLOB column -> source filename. SELECT-list order must match this order.
    static final String[][] CLOB_COLUMNS = {
        {"CW_PROMPT_TEXT", "user.txt"},
        {"SYSTEM_INSTRUCTION", "system.txt"},
        {"CW_RESPONSE_SCHEMA", "schema.json"},
    };

    // Inherited verbatim from the previous version — never retyped.
    static final String[] INHERITED = {
        "CW_PROMPT_LANG", "CW_MODEL_NAME", "CW_MAX_OUTPUT_TOKENS",
        "CW_CALL_TIMEOUT_MILLIS", "CW_TEMPERATURE", "CW_TOP_P", "CW_TOP_K",
        "CW_THINKING_BUDGET", "CW_SEED", "CRITIC_LOOPS", "CW_LOCATION",
    };

    // NOT NULL in the DDL. DEFAULT_PROMPT_VERSION is unused and NOT NULL DEFAULT 'Y',
    // so the table default satisfies it; it is deliberately not named in the insert.
    static final String[] NOT_NULL = {"CW_PROMPT_CODE", "PROMPT_VERSION", "CREATED_BY",
        "CREATE_DATE", "LAST_UPDATED_BY", "LAST_UPDATE_DATE"};
    static final Set<String> HAS_TABLE_DEFAULT =
        new HashSet<>(Arrays.asList("PROMPT_VERSION", "DEFAULT_PROMPT_VERSION"));

    // Python's re treats \s \w \b as Unicode-aware on str; match that.
    static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    static final Pattern INSERT_COLS = Pattern.compile(
        "INSERT\\s+INTO\\s+\\S+\\s*\\((.*?)\\)\\s*SELECT", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | U);
    static final Pattern CHR_RE = Pattern.compile("CHR\\((\\d+)\\)");

    /** Raised for a fatal error: message to stderr, exit 1 (Python's SystemExit(str)). */
    static class Fatal extends RuntimeException {
        Fatal(String msg) { super(msg); }
    }

    // ------------------------------------------------------------------------ generate

    static String encode(String text, String where) {
        for (int i = 0; i < REAL.length; i++) {
            if (text.contains(SENTINEL[i])) {
                throw new Fatal(String.format(
                    "%s contains %s, which is reserved as the sentinel for %s.%n"
                    + "Pick a different sentinel in SENTINEL rather than weakening this check "
                    + "— a sentinel that also occurs naturally silently corrupts the stored text.",
                    where, repr(SENTINEL[i]), repr(REAL[i])));
            }
        }
        for (int i = 0; i < REAL.length; i++) text = text.replace(REAL[i], SENTINEL[i]);
        return text;
    }

    static int utf8Width(int cp) {
        return cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
    }

    /** Split so every piece's VALUE stays under MAX_PIECE_BYTES. Counts code points, not UTF-16 units. */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = utf8Width(cp);
            if (n + w > MAX_PIECE_BYTES && buf.length() > 0) {
                out.add(buf.toString());
                buf.setLength(0);
                n = 0;
            }
            buf.appendCodePoint(cp);
            n += w;
            i += Character.charCount(cp);
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    static List<String> clobPieces(String text, String where) {
        List<String> out = new ArrayList<>();
        for (String c : chunk(encode(text, where))) {
            out.add("    TO_CLOB(TRANSLATE('" + c.replace("'", "''") + "','" + FROM_STR + "'," + TO_EXPR + "))");
        }
        return out;
    }

    /** Read UTF-8 strictly, with Python's universal-newline translation (\r\n and \r -> \n). */
    static String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Fatal(path + " is not valid UTF-8: " + e);
        }
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    static Map<String, String> readSources(String promptDir) throws IOException {
        Map<String, String> payload = new LinkedHashMap<>();
        for (String[] cf : CLOB_COLUMNS) {
            Path path = Paths.get(promptDir, cf[1]);
            if (!Files.exists(path)) throw new Fatal("missing source file: " + path);
            payload.put(cf[0], readText(path));
        }
        String err = Json.check(payload.get("CW_RESPONSE_SCHEMA"));
        if (err != null) throw new Fatal("schema.json is not valid JSON, refusing to store it: " + err);
        return payload;
    }

    static final class Stat {
        final String column, fname;
        final int nchars, npieces;
        Stat(String column, String fname, int nchars, int npieces) {
            this.column = column; this.fname = fname; this.nchars = nchars; this.npieces = npieces;
        }
    }

    static String build(String promptDir, String code, List<Stat> stats) throws IOException {
        Map<String, String> payload = readSources(promptDir);
        String q = "'" + code + "'";
        List<String> L = new ArrayList<>();
        L.add("INSERT INTO " + TABLE + " (");
        L.add("  CW_PROMPT_CODE, PROMPT_VERSION,");
        L.add("  " + String.join(", ", Arrays.copyOfRange(INHERITED, 0, 3)) + ",");
        L.add("  " + String.join(", ", Arrays.copyOfRange(INHERITED, 3, 7)) + ",");
        L.add("  " + String.join(", ", Arrays.copyOfRange(INHERITED, 7, INHERITED.length)) + ",");
        List<String> clobNames = new ArrayList<>();
        for (String[] cf : CLOB_COLUMNS) clobNames.add(cf[0]);
        L.add("  " + String.join(", ", clobNames) + ",");
        L.add("  CREATED_BY, CREATE_DATE, LAST_UPDATED_BY, LAST_UPDATE_DATE");
        L.add(")");
        L.add("SELECT");
        L.add("  P.CW_PROMPT_CODE,");
        L.add("  P.PROMPT_VERSION + 1,");
        for (String c : INHERITED) L.add("  P." + c + ",");
        for (String[] cf : CLOB_COLUMNS) {
            String text = payload.get(cf[0]);
            List<String> pieces = clobPieces(text, promptDir + "/" + cf[1]);
            stats.add(new Stat(cf[0], cf[1], text.codePointCount(0, text.length()), pieces.size()));
            L.add("  (");
            for (int i = 0; i < pieces.size(); i++) L.add(pieces.get(i) + (i == pieces.size() - 1 ? "" : "||"));
            L.add("  ),");
        }
        L.add("  USER,");
        L.add("  SYSTIMESTAMP,");
        L.add("  USER,");
        L.add("  SYSTIMESTAMP");
        L.add("FROM " + TABLE + " P");
        L.add("WHERE P.CW_PROMPT_CODE = " + q);
        L.add("  AND P.PROMPT_VERSION = (SELECT MAX(PROMPT_VERSION) FROM " + TABLE + " WHERE CW_PROMPT_CODE = " + q + ")");
        return String.join("\n", L) + "\n";
    }

    // -------------------------------------------------------------------------- verify

    /** Blank out every '...' literal. The prompt body is full of commas, parens and
     *  semicolons that mean nothing to the parser, so count only outside literals. */
    static String stripLiterals(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("''");
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /** Split on commas at paren depth 0. */
    static List<String> splitTopLevel(String text) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            if (ch == ',' && depth == 0) {
                items.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(ch);
            }
        }
        if (!buf.toString().strip().isEmpty()) items.add(buf.toString());
        return items;
    }

    /** One TO_CLOB(TRANSLATE('lit','from',CHR(n)||...)) match. */
    static final class Piece {
        int start, end;
        String literal, fromStr, toExpr;
    }

    static int skipWs(String s, int i) {
        while (i < s.length() && isPyWhitespace(s.charAt(i))) i++;
        return i;
    }

    static boolean isPyWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85; // == Python str.isspace()
    }

    /** Parse a '...' literal starting at the opening quote; returns index after the
     *  closing quote, or -1. Hand-rolled: a regex alternation over a 3000-char literal
     *  overflows Java's regex stack. */
    static int readLiteral(String s, int i, StringBuilder into) {
        if (i >= s.length() || s.charAt(i) != '\'') return -1;
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') { into.append("''"); i += 2; continue; }
                return i + 1;
            }
            into.append(c);
            i++;
        }
        return -1;
    }

    static Piece tryPiece(String s, int start) {
        final String head = "TO_CLOB(";
        if (!s.startsWith(head, start)) return null;
        int i = skipWs(s, start + head.length());
        if (!s.startsWith("TRANSLATE(", i)) return null;
        i = skipWs(s, i + "TRANSLATE(".length());
        StringBuilder lit = new StringBuilder();
        i = readLiteral(s, i, lit);
        if (i < 0) return null;
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != ',') return null;
        i = skipWs(s, i + 1);
        StringBuilder from = new StringBuilder();
        i = readLiteral(s, i, from);
        if (i < 0) return null;
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != ',') return null;
        i = skipWs(s, i + 1);
        int exprStart = i;
        Matcher m = CHR_RE.matcher(s);
        int exprEnd;
        while (true) {
            if (!m.region(i, s.length()).lookingAt()) return null;
            i = m.end();
            exprEnd = i;
            int j = skipWs(s, i);
            if (s.startsWith("||", j)) {
                int k = skipWs(s, j + 2);
                if (m.region(k, s.length()).lookingAt()) { i = k; continue; }
            }
            break;
        }
        String toExpr = s.substring(exprStart, exprEnd);
        i = skipWs(s, exprEnd);
        if (i >= s.length() || s.charAt(i) != ')') return null;
        i = skipWs(s, i + 1);
        if (i >= s.length() || s.charAt(i) != ')') return null;
        Piece p = new Piece();
        p.start = start;
        p.end = i + 1;
        p.literal = lit.toString();
        p.fromStr = from.toString();
        p.toExpr = toExpr;
        return p;
    }

    static List<Piece> findPieces(String sql) {
        List<Piece> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = sql.indexOf("TO_CLOB(", from);
            if (idx < 0) break;
            Piece p = tryPiece(sql, idx);
            if (p != null) { out.add(p); from = p.end; } else { from = idx + 1; }
        }
        return out;
    }

    static boolean onlyConcat(String between) {
        String t = between;
        int i = skipWs(t, 0);
        if (!t.startsWith("||", i)) return false;
        return skipWs(t, i + 2) == t.length();
    }

    /** Recover decoded text and piece byte sizes per CLOB column. Consecutive pieces
     *  separated by nothing but '||' belong to the same column. */
    static void decodeGroups(String sql, List<String> groups, List<List<Integer>> sizes) {
        List<Piece> matches = findPieces(sql);
        StringBuilder cur = new StringBuilder();
        List<Integer> curSizes = new ArrayList<>();
        for (int idx = 0; idx < matches.size(); idx++) {
            Piece m = matches.get(idx);
            List<Integer> codes = new ArrayList<>();
            Matcher cm = CHR_RE.matcher(m.toExpr);
            while (cm.find()) codes.add(Integer.parseInt(cm.group(1)));
            int fromLen = m.fromStr.codePointCount(0, m.fromStr.length());
            if (fromLen != codes.size()) {
                throw new Fatal(String.format(
                    "TRANSLATE from_string is %d chars but to_string is %d — Oracle silently "
                    + "DELETES the unmatched characters rather than erroring.", fromLen, codes.size()));
            }
            Map<Integer, Integer> table = new LinkedHashMap<>();
            int[] fromCps = m.fromStr.codePoints().toArray();
            for (int k = 0; k < fromCps.length; k++) table.put(fromCps[k], codes.get(k));
            String value = m.literal.replace("''", "'");
            StringBuilder dec = new StringBuilder();
            value.codePoints().forEach(cp -> dec.appendCodePoint(table.getOrDefault(cp, cp)));
            cur.append(dec);
            curSizes.add(value.getBytes(StandardCharsets.UTF_8).length);
            if (idx + 1 < matches.size()) {
                String between = sql.substring(m.end, matches.get(idx + 1).start);
                if (onlyConcat(between)) continue;
            }
            groups.add(cur.toString());
            sizes.add(curSizes);
            cur = new StringBuilder();
            curSizes = new ArrayList<>();
        }
    }

    /** The CLOB columns in the order the INSERT names them, so decoded groups are matched
     *  to the right source file. Falls back to the canonical order when unparseable. */
    static List<String[]> clobColumnOrder(String bareSql) {
        Matcher m = INSERT_COLS.matcher(bareSql);
        List<String[]> canonical = Arrays.asList(CLOB_COLUMNS);
        if (!m.find()) return canonical;
        List<String[]> ordered = new ArrayList<>();
        for (String c : splitTopLevel(m.group(1))) {
            String name = c.strip().toUpperCase();
            for (String[] cf : CLOB_COLUMNS) if (cf[0].equals(name)) ordered.add(cf);
        }
        return ordered.isEmpty() ? canonical : ordered;
    }

    static String cpSlice(String s, int fromCp, int lenCp) {
        int total = s.codePointCount(0, s.length());
        int a = Math.min(fromCp, total), b = Math.min(fromCp + lenCp, total);
        return s.substring(s.offsetByCodePoints(0, a), s.offsetByCodePoints(0, b));
    }

    /** Returns failures (would store wrong data or stop the statement running). Anything
     *  added to warns is safe to run as-is but fragile. */
    static List<String> verify(String sql, String promptDir, List<String> warns) {
        List<String> fails = new ArrayList<>();
        String bare = stripLiterals(sql);

        // the four characters the client eats, across the WHOLE statement
        for (char ch : FORBIDDEN.toCharArray()) {
            long n = sql.chars().filter(c -> c == ch).count();
            if (n > 0) fails.add(String.format("contains %d %s — the client consumes it before Oracle sees it",
                n, repr(String.valueOf(ch))));
        }
        Matcher bind = Pattern.compile(":\\w", U).matcher(sql);
        if (bind.find()) fails.add(String.format("named-bind pattern %s at offset %d",
            repr(bind.group()), sql.codePointCount(0, bind.start())));

        // one statement, no PL/SQL, no comments — checked outside literals only
        Matcher ins = Pattern.compile("\\bINSERT\\s+INTO\\b", Pattern.CASE_INSENSITIVE | U).matcher(bare);
        int nInsert = 0;
        while (ins.find()) nInsert++;
        if (nInsert != 1) fails.add(String.format(
            "found %d INSERT INTO, expected exactly 1 (PreparedStatement takes one statement)", nInsert));
        for (String tok : new String[] {"BEGIN", "DECLARE", "END"}) {
            if (Pattern.compile("\\b" + tok + "\\b", Pattern.CASE_INSENSITIVE | U).matcher(bare).find()) {
                fails.add("PL/SQL token " + repr(tok) + " present — a PreparedStatement cannot run an anonymous block");
            }
        }
        if (bare.contains("--") || bare.contains("/*")) {
            fails.add("comment token present — '--' swallows the rest of a flattened statement");
        }
        if (rstrip(sql).endsWith(";")) fails.add("trailing semicolon — Oracle JDBC rejects it");

        // literal hygiene
        long nMultiline = findPieces(sql).stream().filter(p -> p.literal.contains("\n")).count();
        if (nMultiline > 0) warns.add(String.format(
            "%d literal(s) span a line. The statement runs correctly as-is, but a tool "
            + "that reflows or re-wraps the file would change the stored text. Encoding "
            + "newlines as a sentinel (~ -> CHR(10)) removes the hazard.", nMultiline));

        // decode back and compare; match groups to files by the INSERT column list
        List<String> groups = new ArrayList<>();
        List<List<Integer>> sizes = new ArrayList<>();
        decodeGroups(sql, groups, sizes);
        List<String[]> order = clobColumnOrder(bare);
        if (groups.size() != order.size()) {
            List<String> names = new ArrayList<>();
            for (String[] cf : order) names.add(cf[0]);
            fails.add(String.format("found %d CLOB groups but the INSERT names %d CLOB columns (%s)",
                groups.size(), order.size(), String.join(", ", names)));
        } else {
            for (int g = 0; g < order.size(); g++) {
                String column = order.get(g)[0], fname = order.get(g)[1];
                String decoded = groups.get(g);
                Path path = Paths.get(promptDir, fname);
                String source;
                try {
                    source = readText(path);
                } catch (IOException e) {
                    fails.add("cannot read " + path + ": " + e);
                    continue;
                }
                if (!decoded.equals(source)) {
                    int[] d = decoded.codePoints().toArray(), s = source.codePoints().toArray();
                    int where = Math.min(d.length, s.length);
                    for (int k = 0; k < Math.min(d.length, s.length); k++) if (d[k] != s[k]) { where = k; break; }
                    fails.add(String.format(
                        "%s does NOT round-trip to %s — first difference at character %d "
                        + "(decoded %s vs source %s), decoded %d chars vs source %d",
                        column, fname, where, repr(cpSlice(decoded, where, 30)),
                        repr(cpSlice(source, where, 30)), d.length, s.length));
                }
            }
        }
        int biggest = 0;
        for (List<Integer> g : sizes) for (int b : g) biggest = Math.max(biggest, b);
        if (biggest > ORACLE_LITERAL_CAP) fails.add(String.format(
            "largest literal value is %d bytes, over Oracle's %d-byte cap (the 32767 figure is PL/SQL only)",
            biggest, ORACLE_LITERAL_CAP));

        // column count == select-item count, literals stripped first
        Matcher m = INSERT_COLS.matcher(bare);
        if (!m.find()) {
            fails.add("could not locate the INSERT column list");
        } else {
            List<String> cols = splitTopLevel(m.group(1));
            Matcher sel = Pattern.compile("\\bSELECT\\b(.*?)\\bFROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL | U)
                .matcher(bare.substring(m.end() - "SELECT".length()));
            if (!sel.find()) {
                fails.add("could not locate the SELECT list");
            } else {
                int nitems = splitTopLevel(sel.group(1)).size();
                if (cols.size() != nitems) fails.add(String.format(
                    "INSERT names %d columns but SELECT returns %d items", cols.size(), nitems));
            }
            // NOT NULL coverage
            Set<String> named = new HashSet<>();
            for (String c : cols) named.add(c.strip().toUpperCase());
            for (String col : NOT_NULL) {
                if (!named.contains(col) && !HAS_TABLE_DEFAULT.contains(col)) {
                    fails.add("NOT NULL column " + col + " is neither supplied nor table-defaulted");
                }
            }
        }

        // Charset dependency: only & ; : ? are encoded; everything else travels as a real
        // character and depends on the connection charset. Surface it.
        Map<Integer, Integer> nonAscii = new LinkedHashMap<>();
        for (String gtext : groups) {
            gtext.codePoints().filter(cp -> cp > 127).forEach(cp -> nonAscii.merge(cp, 1, Integer::sum));
        }
        if (!nonAscii.isEmpty()) {
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(nonAscii.entrySet());
            entries.sort((a, b) -> b.getValue() - a.getValue()); // stable, like Python's sorted
            List<String> top = new ArrayList<>();
            for (int k = 0; k < Math.min(6, entries.size()); k++) {
                top.add(repr(new String(Character.toChars(entries.get(k).getKey()))) + " x" + entries.get(k).getValue());
            }
            int total = 0;
            for (int v : nonAscii.values()) total += v;
            warns.add(String.format(
                "%d non-ASCII characters (%s) are stored as real characters, NOT encoded. "
                + "They survive only if the connection charset is AL32UTF8; on a single-byte "
                + "charset the driver silently converts them to a question mark. Confirm "
                + "NLS_CHARACTERSET and spot-check DBMS_LOB.GETLENGTH after the insert.",
                total, String.join(", ", top)));
        }

        // schema.json is valid JSON
        Path schemaPath = Paths.get(promptDir, "schema.json");
        if (Files.exists(schemaPath)) {
            try {
                String err = Json.check(readText(schemaPath));
                if (err != null) fails.add("schema.json is not valid JSON: " + err);
            } catch (IOException e) {
                fails.add("schema.json is not valid JSON: " + e);
            }
        }
        return fails;
    }

    static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && isPyWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Approximation of Python repr() for short strings, used in messages only. */
    static String repr(String s) {
        String q = s.contains("'") && !s.contains("\"") ? "\"" : "'";
        StringBuilder b = new StringBuilder(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (String.valueOf(c).equals(q)) b.append('\\').append(c);
            else if (c < 0x20 || c == 0x7f) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.append(q).toString();
    }

    // ------------------------------------------------------------------ minimal JSON check

    /** Strict JSON validator (no dependencies). Returns null if valid, else an error. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static String check(String text) {
            Json j = new Json(text);
            try {
                j.ws();
                j.value();
                j.ws();
                if (j.i != j.s.length()) return j.err("Extra data");
                return null;
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        }

        private String err(String what) {
            int line = 1, col = 1;
            for (int k = 0; k < i && k < s.length(); k++) {
                if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
            }
            return String.format("%s: line %d column %d (char %d)", what, line, col, i);
        }

        private IllegalStateException fail(String what) { return new IllegalStateException(err(what)); }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        private void value() {
            if (i >= s.length()) throw fail("Expecting value");
            char c = s.charAt(i);
            if (c == '{') object();
            else if (c == '[') array();
            else if (c == '"') string();
            else if (c == '-' || (c >= '0' && c <= '9')) number();
            else if (!(lit("true") || lit("false") || lit("null") || lit("NaN") || lit("Infinity")))
                throw fail("Expecting value");
        }

        private boolean lit(String word) {
            if (s.startsWith(word, i)) { i += word.length(); return true; }
            return false;
        }

        private void object() {
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"')
                    throw fail("Expecting property name enclosed in double quotes");
                string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw fail("Expecting ':' delimiter");
                i++;
                ws();
                value();
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; return; }
                throw fail("Expecting ',' delimiter");
            }
        }

        private void array() {
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return; }
            while (true) {
                ws();
                value();
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; return; }
                throw fail("Expecting ',' delimiter");
            }
        }

        private void string() {
            i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') { i++; return; }
                if (c < 0x20) throw fail("Invalid control character at");
                if (c == '\\') {
                    if (i + 1 >= s.length()) break;
                    char e = s.charAt(i + 1);
                    if ("\"\\/bfnrt".indexOf(e) >= 0) { i += 2; continue; }
                    if (e == 'u' && i + 5 < s.length()
                        && s.substring(i + 2, i + 6).matches("[0-9a-fA-F]{4}")) { i += 6; continue; }
                    throw fail("Invalid \\escape");
                }
                i++;
            }
            throw fail("Unterminated string starting at");
        }

        private void number() {
            if (lit("-Infinity")) return;
            Matcher m = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?").matcher(s);
            if (!m.region(i, s.length()).lookingAt()) throw fail("Expecting value");
            i = m.end();
        }
    }

    // ------------------------------------------------------------------------------ cli

    static final String USAGE =
        "usage:\n"
        + "  java OraclePromptSql.java gen <prompt-dir> --code <CW_PROMPT_CODE> [-o FILE]\n"
        + "  java OraclePromptSql.java verify <sql-file> <prompt-dir>\n";

    static int usage(String msg) {
        System.err.print(USAGE);
        if (msg != null) System.err.println("error: " + msg);
        return 2;
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0) return usage("the following arguments are required: cmd");
        String cmd = args[0];
        if (cmd.equals("-h") || cmd.equals("--help")) { System.out.print(USAGE); return 0; }

        if (cmd.equals("gen")) {
            String dir = null, code = null, out = null;
            for (int k = 1; k < args.length; k++) {
                String a = args[k];
                if (a.equals("--code")) { if (++k >= args.length) return usage("--code expects a value"); code = args[k]; }
                else if (a.startsWith("--code=")) code = a.substring(7);
                else if (a.equals("-o") || a.equals("--out")) { if (++k >= args.length) return usage(a + " expects a value"); out = args[k]; }
                else if (a.startsWith("--out=")) out = a.substring(6);
                else if (a.equals("-h") || a.equals("--help")) { System.out.print(USAGE); return 0; }
                else if (a.startsWith("-")) return usage("unrecognized argument: " + a);
                else if (dir == null) dir = a;
                else return usage("unrecognized argument: " + a);
            }
            if (dir == null) return usage("the following arguments are required: prompt_dir");
            if (code == null) return usage("the following arguments are required: --code");
            int codeLen = code.codePointCount(0, code.length());
            if (codeLen > 50) throw new Fatal("CW_PROMPT_CODE is " + codeLen + " chars, column is VARCHAR2(50 CHAR)");

            List<Stat> stats = new ArrayList<>();
            String sql = build(dir, code, stats);
            List<String> warns = new ArrayList<>();
            List<String> fails = verify(sql, dir, warns);
            if (!fails.isEmpty()) {
                System.err.println("REFUSING TO WRITE — generated SQL failed verification:");
                for (String f : fails) System.err.println("  - " + f);
                return 1;
            }
            if (out == null) out = Paths.get(dir, "insert_prompt.sql").toString();
            Files.write(Paths.get(out), sql.getBytes(StandardCharsets.UTF_8));
            System.out.println(out + "  (" + code + ")");
            for (Stat st : stats) {
                System.out.println(String.format("   %-20s %-11s %7d chars  %3d pieces", st.column, st.fname, st.nchars, st.npieces));
            }
            long lines = sql.chars().filter(c -> c == '\n').count();
            System.out.println(String.format("   statement: %d bytes, %d lines, verified: round-trips exactly, no & ; : ?",
                sql.getBytes(StandardCharsets.UTF_8).length, lines));
            for (String w : warns) System.out.println("   NOTE: " + w);
            return 0;
        }

        if (cmd.equals("verify")) {
            List<String> pos = new ArrayList<>();
            for (int k = 1; k < args.length; k++) {
                if (args[k].equals("-h") || args[k].equals("--help")) { System.out.print(USAGE); return 0; }
                pos.add(args[k]);
            }
            if (pos.size() != 2) return usage("verify expects <sql-file> <prompt-dir>");
            String sql = readText(Paths.get(pos.get(0)));
            List<String> warns = new ArrayList<>();
            List<String> fails = verify(sql, pos.get(1), warns);
            if (!fails.isEmpty()) {
                System.out.println("FAIL (" + fails.size() + ")");
                for (String f : fails) System.out.println("  - " + f);
            } else {
                System.out.println("PASS — every CLOB round-trips byte-identically; no & ; : ? ; one statement; "
                    + "all literals under " + ORACLE_LITERAL_CAP + " bytes");
            }
            for (String w : warns) System.out.println("  NOTE: " + w);
            return fails.isEmpty() ? 0 : 1;
        }

        return usage("invalid choice: " + repr(cmd) + " (choose from 'gen', 'verify')");
    }

    public static void main(String[] args) {
        int rc;
        try {
            rc = run(args);
        } catch (Fatal e) {
            System.err.println(e.getMessage());
            rc = 1;
        } catch (IOException e) {
            System.err.println(e);
            rc = 1;
        }
        System.exit(rc);
    }
}
