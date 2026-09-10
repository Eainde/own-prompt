#!/usr/bin/env sh
# Launcher for macOS / Linux. Runs the Python tool if Python 3.7+ is available,
# otherwise the Java port (JDK 11+). Both produce byte-identical SQL.
#
#   ./oracle-prompt-sql.sh gen <prompt-dir> --code <CW_PROMPT_CODE> [-o FILE]
#   ./oracle-prompt-sql.sh verify <sql-file> <prompt-dir>
#
# Force a runtime with ORACLE_SQL_RUNTIME=python or ORACLE_SQL_RUNTIME=java.

DIR="$(cd "$(dirname "$0")" && pwd)"

has_python() {
    command -v python3 >/dev/null 2>&1 &&
        python3 -c 'import sys; sys.exit(sys.version_info < (3, 7))' >/dev/null 2>&1
}

run_python() { exec python3 "$DIR/oracle_prompt_sql.py" "$@"; }
run_java()   { exec java "$DIR/OraclePromptSql.java" "$@"; }

case "${ORACLE_SQL_RUNTIME:-}" in
    python) run_python "$@" ;;
    java)   run_java "$@" ;;
esac

if has_python; then
    run_python "$@"
elif command -v java >/dev/null 2>&1; then
    run_java "$@"
else
    echo "oracle-prompt-sql: need Python 3.7+ or Java 11+ on PATH." >&2
    exit 127
fi
