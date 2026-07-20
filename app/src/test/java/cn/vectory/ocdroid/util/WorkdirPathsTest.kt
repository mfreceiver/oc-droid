package cn.vectory.ocdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * slimapi v0.2.2 client-adapt T4 — coverage for the NEW server-facing
 * [WorkdirPaths.normalizeDirectory].
 *
 * `normalizeDirectory` is intentionally DISTINCT from the existing
 * [WorkdirPaths.normalize] (which is comparison-keying only, raw string
 * passed to server API). `normalizeDirectory` is the server-facing form
 * that aligns with oc-slimapi `normalize_directory`:
 * strip trailing slash, preserve root `/`. This test pins the four
 * criterion-matrix cases (T4-C1): trailing-slash strip, root `/`
 * preservation, empty-`""` → `/`, and idempotence.
 */
class WorkdirPathsTest {

    @Test
    fun `normalizeDirectory strips trailing slash preserves root`() {
        // T4-C1 matrix — aligns with oc-slimapi normalize_directory:
        //   s.rstrip("/") or "/"  →  "" → "/", "/" → "/", "/app/" → "/app".
        assertEquals("/app", WorkdirPaths.normalizeDirectory("/app/"))
        assertEquals("/app", WorkdirPaths.normalizeDirectory("/app"))
        assertEquals("/", WorkdirPaths.normalizeDirectory("/"))
        assertEquals("/", WorkdirPaths.normalizeDirectory(""))
    }

    @Test
    fun `normalizeDirectory is idempotent`() {
        // Applying twice MUST equal applying once (server normalizes the
        // same way, so re-normalizing a client-normalized value is a no-op;
        // otherwise the client could drift from the server on retry).
        val cases = listOf("", "/", "/app", "/app/", "/a/b/c", "/a/b/c/")
        cases.forEach { dir ->
            val once = WorkdirPaths.normalizeDirectory(dir)
            val twice = WorkdirPaths.normalizeDirectory(once)
            assertEquals("idempotent for \"$dir\"", once, twice)
        }
    }

    @Test
    fun `normalizeDirectory strips multiple trailing slashes`() {
        // Defensive: server `rstrip("/")` strips ALL trailing slashes, so
        // the client-side normalize must agree (else a `/app//` fan-out
        // entry would survive client normalize but get stripped server-side
        // → fan-out count mismatch, the exact bug T4 fixes).
        assertEquals("/app", WorkdirPaths.normalizeDirectory("/app//"))
        assertEquals("/app", WorkdirPaths.normalizeDirectory("/app///"))
        assertEquals("/", WorkdirPaths.normalizeDirectory("//"))
    }

    @Test
    fun `normalizeDirectory trims surrounding whitespace first`() {
        // Real stored-form workdirs can pick up surrounding whitespace
        // (copy-paste / clipboard import). Server treats the trimmed form;
        // client must agree so the same logical workdir fans out once.
        assertEquals("/app", WorkdirPaths.normalizeDirectory("  /app/  "))
        assertEquals("/", WorkdirPaths.normalizeDirectory("   "))
        assertEquals("/", WorkdirPaths.normalizeDirectory("\t/\t"))
    }

    @Test
    fun `normalizeDirectory preserves interior slashes`() {
        // Only TRAILING slashes are stripped; interior path segments are
        // left intact (the server does not collapse `//` mid-path either —
        // root preserve + trailing strip is the entire transform).
        assertEquals("/a/b/c", WorkdirPaths.normalizeDirectory("/a/b/c/"))
        assertEquals("/a/b/c", WorkdirPaths.normalizeDirectory("/a/b/c"))
    }
}
