package com.skillmasterai;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ModuleMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Approximates §2.5 rule 1 — "a module may only read and write the tables it owns" — as a
 * source lint over string literals.
 *
 * <p><strong>This is a lint, not a proof, and it is worth being precise about why.</strong> It
 * reads Java source text, so SQL assembled at runtime, built from fragments, hidden in a resource
 * file, or written with a table alias it does not know about will all slip past. A table named
 * only in Javadoc {@code {@code …}} is invisible to it too. What it does catch is the ordinary
 * mistake: a query in one module reaching for another module's table. The mechanism that would
 * actually guarantee the rule is a PostgreSQL role per module with {@code GRANT} on its own
 * tables only, which needs one DataSource per module and far more machinery than P0 justifies.
 * Noted here so the gap is visible rather than assumed closed.
 *
 * <p>Only string literals are inspected — comments are skipped. Scanning whole files would flag
 * every identifier that happens to share a table's name, and scanning comments would flag prose
 * (this class's own sibling once tripped over a Javadoc phrase in quotes).
 */
class TableOwnershipTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final String COMMON_PACKAGE = ModuleMap.BASE_PACKAGE + ".common";

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /*
     * Case-insensitive and leading-whitespace tolerant on purpose: PostgreSQL accepts either
     * spelling and both are legal SQL, so a parse that only recognises the loud one would let the
     * quiet one through as agreement — the set would simply lack the table on both sides.
     */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(\\w+)",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    @Test
    void everyTableInTheBaselineIsOwnedByExactlyOneModule() throws IOException {
        // Pins the map against the migrations in both directions, which is what this test has
        // always claimed to do. Counting the map's own entries could not do it: a table added to a
        // migration and never registered leaves the count where it was and passed here, which is
        // exactly the case §2.5 rule 1 exists to catch. ModuleMap throws if two modules claim the
        // same table.
        //
        // Every migration file, not just the baseline: the next table arrives in a later one, and
        // reading only V1 would leave it unowned and touchable from any module with nothing to
        // notice.
        assertThat(Files.isDirectory(MIGRATIONS))
                .as("expected to run from the module directory, so %s resolves", MIGRATIONS)
                .isTrue();

        Set<String> declared = new HashSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                Matcher matcher = CREATE_TABLE.matcher(
                        Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    declared.add(matcher.group(1));
                }
            }
        }

        assertThat(declared).as("%s should declare tables at all", MIGRATIONS).isNotEmpty();
        assertThat(ModuleMap.tableOwners().keySet())
                .as("every table the migrations declare, and only those, should have one owner")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void aTableIsOnlyNamedInsideItsOwnersPackage() throws IOException {
        assertThat(Files.isDirectory(SOURCES))
                .as("expected to run from the module directory, so %s resolves", SOURCES)
                .isTrue();

        Map<String, ModuleMap.Module> owners = ModuleMap.tableOwners();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String packageName = packageOf(source);

                // ModuleMap declares the whole map, so it necessarily names every table.
                if (packageName.startsWith(COMMON_PACKAGE)) {
                    continue;
                }

                String queries = stringLiteralsIn(source);
                for (Map.Entry<String, ModuleMap.Module> entry : owners.entrySet()) {
                    ModuleMap.Module owner = entry.getValue();
                    if (mentionsTable(queries, entry.getKey())
                            && !isInside(packageName, owner.packageName())) {
                        violations.add("%s names table '%s', which belongs to %s (%s)"
                                .formatted(file, entry.getKey(), owner.id(), owner.packageName()));
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    /**
     * The scan is only worth trusting if it can tell code from prose, so that part is pinned
     * here rather than discovered by a confusing failure elsewhere.
     */
    @Test
    void literalsAreScannedButCommentsAndCharactersAreNot() {
        String source = """
                // "skill" in a line comment
                /* "skill_version" in a block comment */
                class Example {
                    char quote = '"';
                    String sql = "SELECT * FROM blob";
                    String doc = \"\"\"
                            "namespace" inside a text block
                            \"\"\";
                }
                """;

        String literals = stringLiteralsIn(source);

        assertThat(literals).contains("SELECT * FROM blob").contains("\"namespace\" inside a text block");
        assertThat(literals).doesNotContain("in a line comment").doesNotContain("in a block comment");
        // The character literal must not have been mistaken for the start of a string, which
        // would swallow everything up to the next quote and corrupt the rest of the file.
        assertThat(literals).contains("SELECT * FROM blob");
    }

    /**
     * The matcher itself, which the rule below can only exercise negatively.
     *
     * <p>"Violations is empty" is equally true when nothing is ever matched, so a one-word edit that
     * makes {@link #mentionsTable} always answer false switches rule 1's code-level half off and
     * leaves the suite green — which is what this pins.
     */
    @Test
    void aTableIsRecognisedOnlyWhereSqlNamesOne() {
        assertThat(mentionsTable("SELECT * FROM skill WHERE id = 1", "skill")).isTrue();
        assertThat(mentionsTable("INSERT INTO blob_content (sha256) VALUES ('x')", "blob_content"))
                .isTrue();
        assertThat(mentionsTable("UPDATE skill_version SET deleted_at = 1", "skill_version"))
                .isTrue();
        assertThat(mentionsTable("DELETE FROM audit_event", "audit_event")).isTrue();
        assertThat(mentionsTable("LOCK TABLE version_file IN SHARE MODE", "version_file")).isTrue();

        assertThat(mentionsTable("SELECT * FROM skill_version", "skill"))
                .as("'_' is a word character, so this does not mention the 'skill' table")
                .isFalse();
        assertThat(mentionsTable("the skill has no SKILL.md at its root", "skill"))
                .as("prose is not a query — the false positive the SQL-context rule exists to remove")
                .isFalse();
    }

    private static String packageOf(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isInside(String packageName, String modulePackage) {
        return packageName.equals(modulePackage) || packageName.startsWith(modulePackage + ".");
    }

    /**
     * Whether a query actually names the table — that is, whether the name follows one of the
     * keywords that can introduce one.
     *
     * <p>Bare word matching was tried first and was wrong: several tables are ordinary English
     * words, so {@code "the skill has no SKILL.md at its root"} looked like a reference to the
     * {@code skill} table. Requiring SQL context removes that whole class of false positive and
     * still catches what this is for — a query in one module reading another module's table.
     *
     * <p>Word boundaries matter too: {@code '_'} is a word character, so {@code \bskill\b} does not
     * match inside {@code skill_version}, and every table would otherwise appear to be several.
     */
    static boolean mentionsTable(String queries, String table) {
        return Pattern.compile("(?i)\\b(?:from|join|into|update|table)\\s+" + Pattern.quote(table) + "\\b")
                .matcher(queries)
                .find();
    }

    /**
     * Returns the contents of every string literal and text block, and nothing else.
     *
     * <p>Written as a scanner rather than a regex because the three constructs interleave: a
     * {@code //} inside a string is not a comment, a quote inside a comment is not a literal, and
     * a char literal such as {@code '"'} would otherwise look like the start of one.
     */
    static String stringLiteralsIn(String source) {
        StringBuilder literals = new StringBuilder();
        int i = 0;
        int n = source.length();

        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                literals.append(source, i + 3, end < 0 ? n : end).append('\n');
                i = end < 0 ? n : end + 3;
            } else if (c == '"') {
                StringBuilder content = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        content.append(source.charAt(j + 1));
                        j += 2;
                    } else if (d == '"' || d == '\n') {
                        break;
                    } else {
                        content.append(d);
                        j++;
                    }
                }
                literals.append(content).append('\n');
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                    } else if (d == '\'') {
                        break;
                    } else {
                        j++;
                    }
                }
                i = j + 1;
            } else {
                i++;
            }
        }

        return literals.toString();
    }
}
