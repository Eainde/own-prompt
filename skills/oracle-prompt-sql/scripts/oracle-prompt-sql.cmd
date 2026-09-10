@echo off
rem Launcher for Windows. Runs the Python tool if Python 3.7+ is available,
rem otherwise the Java port (JDK 11+). Both produce the same SQL.
rem
rem   oracle-prompt-sql.cmd gen <prompt-dir> --code <CW_PROMPT_CODE> [-o FILE]
rem   oracle-prompt-sql.cmd verify <sql-file> <prompt-dir>
rem
rem Force a runtime with: set ORACLE_SQL_RUNTIME=python   or   set ORACLE_SQL_RUNTIME=java
setlocal
set "DIR=%~dp0"
set "PYTHONUTF8=1"

if /i "%ORACLE_SQL_RUNTIME%"=="java" goto :java
if /i "%ORACLE_SQL_RUNTIME%"=="python" goto :python_any

rem "py" is the official Windows launcher; plain "python" may be the Microsoft Store
rem stub, which fails the version probe and so falls through to Java.
py -3 -c "import sys; sys.exit(sys.version_info < (3, 7))" >nul 2>&1
if not errorlevel 1 goto :py_launcher
python -c "import sys; sys.exit(sys.version_info < (3, 7))" >nul 2>&1
if not errorlevel 1 goto :py_plain
goto :java

:python_any
py -3 -c "import sys" >nul 2>&1
if not errorlevel 1 goto :py_launcher
goto :py_plain

:py_launcher
py -3 "%DIR%oracle_prompt_sql.py" %*
exit /b %errorlevel%

:py_plain
python "%DIR%oracle_prompt_sql.py" %*
exit /b %errorlevel%

:java
where java >nul 2>&1
if errorlevel 1 goto :none
java "%DIR%OraclePromptSql.java" %*
exit /b %errorlevel%

:none
echo oracle-prompt-sql: need Python 3.7+ or Java 11+ on PATH. 1>&2
exit /b 127
