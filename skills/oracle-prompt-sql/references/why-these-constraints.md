# Why the SQL looks like this

Every rule in SKILL.md exists because something broke. Read this before relaxing one, or when a
reviewer asks why the statement is written so oddly.

## "Just use a PL/SQL block with DBMS_LOB.APPEND"

This is the natural answer to "build a 110 KB CLOB", and an earlier iteration of this work used
it. Inside PL/SQL the literal cap is 32767 rather than 4000, so the chunking gets easier, and
`DBMS_LOB.APPEND` reads clearly.

It was **correct and unusable**. The consumer is a Java `PreparedStatement` handed the whole
query as one string, and it accepts exactly one statement. An anonymous block is not a statement
the driver will take. The same reasoning rules out a companion `UPDATE`, a `COMMIT`, and every
SQL\*Plus directive (`SET DEFINE OFF`, `/`, `WHENEVER SQLERROR`) — those are client commands the
driver never sees, so adding them changes nothing except making the file look safe.

If you find yourself reaching for PL/SQL because chunking is tedious, the answer is to run the
script, not to change the target.

## "Escape the colons"

You cannot escape your way out of `:`. The character is consumed by the **client** — the Oracle
JDBC driver and Spring's `NamedParameterJdbcTemplate` both scan for `:word` and replace it with a
bind parameter *before Oracle parses anything*. Doubling it, backslashing it, or wrapping the
literal in `q'[...]'` does not help, because none of that is interpreted at the layer that eats
the colon. The symptom is an error about a missing IN/OUT parameter, or a prompt that silently
arrives with holes in it.

Same story for `&` (substitution variable), `;` (statement terminator) and `?` (positional bind).

Encoding via `TRANSLATE` works because it removes the characters from the SQL **text** entirely
and has Oracle reconstruct them from `CHR()` codes at execution. There is no `:` left for the
client to find.

Padding a colon to ` : ` "fixes" the parse and corrupts the stored prompt. Don't.

## "The literal cap is 32767"

That figure is **PL/SQL only**. A SQL string literal is capped at **4000 bytes**, and
`MAX_STRING_SIZE=EXTENDED` — which would raise it — is not safe to assume about a database you
do not control. Exceeding it gives `ORA-01704: string literal too long`.

Two details that make this trickier than it looks:

- **Bytes, not characters.** The prompts are UTF-8 with `§ × ü — → ≥` in them. A character count
  under 4000 can still be over 4000 bytes.
- **Measure the decoded value, not the source text.** A literal containing `''` (an escaped
  quote) is two source characters for one stored character. Measuring the source over-counts and
  makes you chunk more finely than necessary; measuring the wrong direction would let a piece
  through that is actually too long.

The 3000-byte working budget leaves room for a multi-byte character landing on a chunk boundary.

## Why `INSERT ... SELECT` rather than `INSERT ... VALUES`

Two reasons, and the second is the one people forget.

1. It gets `PROMPT_VERSION + 1` without a second statement.
2. It **inherits every config column from the previous version**, so model name, token budget,
   timeout, temperature and the rest are never retyped. Retyping them is how a prompt quietly
   gets deployed at the wrong temperature — a change nobody sees in review, because the diff
   looks like a prompt change.

The cost is the silent no-op: with no previous row the `SELECT` matches nothing and the insert
affects 0 rows **without raising an error**. Check the update count on every deploy.

## Why the verifier decodes rather than inspects

The statement is 100 KB+ of encoded text. A dropped character, a mis-escaped quote, or a chunk
boundary that lands inside a multi-byte character produces SQL that looks completely normal and
stores a subtly wrong prompt. Nothing about that is visible by reading.

The only check that catches it is to decode every literal back through the same `TRANSLATE` map
and compare byte-for-byte with the source file. Everything else the verifier does is cheap
insurance around that one check.

A corollary worth internalising: **chunk boundaries carry no meaning.** Two correct statements
can split the same prompt at different offsets and both be right. When comparing two generated
files, compare decoded content — a textual diff of the SQL will show differences that mean
nothing.

## Traps in the sentinel scheme

- **`TRANSLATE` deletes rather than errors.** If `to_string` is shorter than `from_string`,
  Oracle silently removes the characters that have no replacement. There is no warning, and the
  stored prompt just quietly loses every `:`. Both strings must be the same length, and the
  verifier asserts it.
- **A sentinel must not occur naturally in any source file.** `~ ^ ! * @` were chosen because
  they were the only printable ASCII characters absent from every prompt. If a prompt edit
  introduces one, generation fails loudly — that is deliberate. Pick a different sentinel rather
  than suppressing the check; the alternative is silent corruption of the stored text.
- **Newlines go through the same map** (`~` → `CHR(10)`), which is what lets the statement be
  reflowed onto a single line safely. It also means no literal ever spans a line, so a tool that
  rewraps the file cannot change its meaning.

## Why literal-stripping matters when checking the statement

Prompt text legitimately contains `;`, commas, parentheses, and the word `BEGIN`. Any check that
counts commas, looks for a second statement, or hunts for PL/SQL tokens must blank out string
literals first, or it will report failures that do not exist — and, worse, teach whoever reads
the output to ignore the checker.
