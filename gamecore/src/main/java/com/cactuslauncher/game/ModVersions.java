package com.cactuslauncher.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Version predicates of mod metadata: Fabric/Quilt semantic-version predicates ({@code >=1.2 <2}, {@code ~1.2},
 * {@code ^1.2}, {@code 1.20.x}, arrays meaning "any of") and Maven version ranges of Forge/NeoForge
 * ({@code [47,)}, {@code [1.20,1.21)}, unions).
 */
public final class ModVersions {
    private ModVersions() {}

    /** A parsed version requirement. */
    public interface Range {
        boolean test(String version);
        /** Short Russian-readable form such as "≥ 0.92.0"; empty for "any version". */
        String display();
        default boolean any() { return display().isEmpty(); }
    }

    public static final Range ANY = new Range() {
        @Override public boolean test(String version) { return true; }
        @Override public String display() { return ""; }
        @Override public String toString() { return "*"; }
    };

    // ---------------------------------------------------------------- Fabric semantic versions

    /** Fabric's SemanticVersion: numeric components, optional pre-release, build metadata ignored. */
    static final class SemVer implements Comparable<SemVer> {
        final long[] parts;
        final String pre;

        private SemVer(long[] parts, String pre) { this.parts = parts; this.pre = pre; }

        /** Null when [text] is not a semantic version (Fabric then compares it as a plain string). */
        static SemVer parse(String text) {
            if (text == null) return null;
            String value = text.strip();
            int plus = value.indexOf('+');
            if (plus >= 0) value = value.substring(0, plus);
            String pre = null;
            int dash = value.indexOf('-');
            if (dash >= 0) {
                pre = value.substring(dash + 1);
                value = value.substring(0, dash);
            }
            if (value.isEmpty()) return null;
            String[] pieces = value.split("\\.", -1);
            long[] parts = new long[pieces.length];
            for (int i = 0; i < pieces.length; i++) {
                String piece = pieces[i];
                if (piece.isEmpty() || piece.length() > 18 || !piece.chars().allMatch(Character::isDigit)) return null;
                parts[i] = Long.parseLong(piece);
            }
            return new SemVer(parts, pre);
        }

        long part(int index) { return index < parts.length ? parts[index] : 0; }

        @Override public int compareTo(SemVer other) {
            int length = Math.max(parts.length, other.parts.length);
            for (int i = 0; i < length; i++) {
                int result = Long.compare(part(i), other.part(i));
                if (result != 0) return result;
            }
            if (pre == null) return other.pre == null ? 0 : 1;
            if (other.pre == null) return -1;
            return comparePre(pre, other.pre);
        }

        private static int comparePre(String left, String right) {
            String[] a = left.split("\\.", -1), b = right.split("\\.", -1);
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                boolean an = numeric(a[i]), bn = numeric(b[i]);
                int result;
                if (an && bn) result = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
                else if (an) result = -1;
                else if (bn) result = 1;
                else result = a[i].compareTo(b[i]);
                if (result != 0) return result;
            }
            return Integer.compare(a.length, b.length);
        }

        private static boolean numeric(String value) {
            return !value.isEmpty() && value.length() <= 18 && value.chars().allMatch(Character::isDigit);
        }
    }

    private enum Op { ANY, EQ, GE, GT, LE, LT, TILDE, CARET, PREFIX }

    private record Term(Op op, String raw, SemVer version, int prefixLength) {
        boolean test(String installed) {
            if (op == Op.ANY) return true;
            SemVer value = SemVer.parse(installed);
            if (version == null || value == null) {
                // Non-semantic versions only support equality.
                return (op == Op.EQ) && raw.equals(installed == null ? null : installed.strip());
            }
            int compare = value.compareTo(version);
            return switch (op) {
                case EQ -> compare == 0;
                case GE -> compare >= 0;
                case GT -> compare > 0;
                case LE -> compare <= 0;
                case LT -> compare < 0;
                case TILDE -> compare >= 0 && value.part(0) == version.part(0) && value.part(1) == version.part(1);
                case CARET -> compare >= 0 && value.part(0) == version.part(0);
                case PREFIX -> {
                    for (int i = 0; i < prefixLength; i++) if (value.part(i) != version.part(i)) yield false;
                    yield true;
                }
                case ANY -> true;
            };
        }

        String display() {
            return switch (op) {
                case ANY -> "";
                case EQ -> raw;
                case GE -> "≥ " + raw;
                case GT -> "> " + raw;
                case LE -> "≤ " + raw;
                case LT -> "< " + raw;
                case TILDE -> prefix(2) + ".x, ≥ " + raw;
                case CARET -> "≥ " + raw;
                case PREFIX -> prefix(prefixLength) + ".x";
            };
        }

        private String prefix(int length) {
            var pieces = new ArrayList<String>();
            for (int i = 0; i < Math.max(1, length); i++) pieces.add(Long.toString(version.part(i)));
            return String.join(".", pieces);
        }
    }

    private static Term term(String text) {
        String value = text.strip();
        if (value.isEmpty() || value.equals("*")) return new Term(Op.ANY, "", null, 0);
        Op op = Op.EQ;
        for (var prefix : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
            if (value.startsWith(prefix)) {
                op = switch (prefix) {
                    case ">=" -> Op.GE; case "<=" -> Op.LE; case ">" -> Op.GT; case "<" -> Op.LT;
                    case "~" -> Op.TILDE; case "^" -> Op.CARET; default -> Op.EQ;
                };
                value = value.substring(prefix.length()).strip();
                break;
            }
        }
        // Wildcards: "1.20.x" / "1.20.*" / "1.x".
        String[] pieces = value.split("\\.", -1);
        int wildcard = -1;
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.equals("x") || piece.equals("X") || piece.equals("*")) { wildcard = i; break; }
        }
        if (wildcard == 0) return new Term(Op.ANY, "", null, 0);
        if (wildcard > 0) {
            String base = String.join(".", java.util.Arrays.copyOf(pieces, wildcard));
            SemVer version = SemVer.parse(base);
            if (version == null) return new Term(Op.EQ, value, null, 0);
            return switch (op) {
                case EQ, TILDE, CARET -> new Term(Op.PREFIX, base, version, wildcard);
                default -> new Term(op, base, version, 0);
            };
        }
        return new Term(op, value, SemVer.parse(value), 0);
    }

    /** Fabric predicate: space-separated terms must all match. */
    private record FabricAll(List<Term> terms) implements Range {
        @Override public boolean test(String version) {
            for (Term term : terms) if (!term.test(version)) return false;
            return true;
        }
        @Override public String display() {
            var parts = new ArrayList<String>();
            for (Term term : terms) if (!term.display().isEmpty()) parts.add(term.display());
            return String.join(", ", parts);
        }
    }

    /** Any of the alternatives (Fabric arrays, Maven unions). */
    private record AnyOf(List<Range> options) implements Range {
        @Override public boolean test(String version) {
            for (Range option : options) if (option.test(version)) return true;
            return options.isEmpty();
        }
        @Override public String display() {
            var parts = new ArrayList<String>();
            for (Range option : options) {
                if (option.any()) return "";
                parts.add(option.display());
            }
            return String.join(" или ", parts);
        }
    }

    /** One Fabric/Quilt predicate string, e.g. {@code ">=0.92.0 <0.93"}; "||" is also read as "any of". */
    public static Range fabric(String predicate) {
        if (predicate == null) return ANY;
        if (predicate.contains("||")) {
            var options = new ArrayList<String>();
            for (String option : predicate.split("\\|\\|")) options.add(option);
            return fabric(options);
        }
        var terms = new ArrayList<Term>();
        for (String piece : normalizeOperators(predicate).strip().split("\\s+")) {
            if (piece.isEmpty()) continue;
            Term term = term(piece);
            if (term.op() != Op.ANY) terms.add(term);
        }
        return terms.isEmpty() ? ANY : new FabricAll(terms);
    }

    /** A Fabric array of predicates: any one of them must match. */
    public static Range fabric(List<String> predicates) {
        if (predicates == null || predicates.isEmpty()) return ANY;
        if (predicates.size() == 1) return fabric(predicates.get(0));
        var options = new ArrayList<Range>();
        for (String predicate : predicates) options.add(fabric(predicate));
        return new AnyOf(options);
    }

    /** "&gt;= 1.2" is written with a space by some mods; glue the operator to its version. */
    private static String normalizeOperators(String predicate) {
        return predicate.replaceAll("(>=|<=|>|<|=|~|\\^)\\s+", "$1");
    }

    // ---------------------------------------------------------------- Maven ranges (Forge, NeoForge)

    /** Maven ComparableVersion ordering, close enough for mod versions. */
    static int compareMaven(String left, String right) {
        List<Object> a = mavenItems(left), b = mavenItems(right);
        int length = Math.max(a.size(), b.size());
        for (int i = 0; i < length; i++) {
            Object x = i < a.size() ? a.get(i) : null, y = i < b.size() ? b.get(i) : null;
            int result = compareItem(x, y);
            if (result != 0) return result;
        }
        return 0;
    }

    private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

    private static int compareItem(Object x, Object y) {
        if (x == null && y == null) return 0;
        if (x == null) return -compareItem(y, null);
        if (x instanceof Long number) {
            if (y == null) return number == 0 ? 0 : 1;
            if (y instanceof Long other) return Long.compare(number, other);
            return 1;
        }
        String qualifier = (String) x;
        if (y == null) return compareQualifier(qualifier, "");
        if (y instanceof Long) return -1;
        return compareQualifier(qualifier, (String) y);
    }

    private static int compareQualifier(String a, String b) {
        return qualifierKey(a).compareTo(qualifierKey(b));
    }

    private static String qualifierKey(String qualifier) {
        int index = QUALIFIERS.indexOf(qualifier);
        return index >= 0 ? Integer.toString(index) : QUALIFIERS.size() + "-" + qualifier;
    }

    private static List<Object> mavenItems(String version) {
        String value = version == null ? "" : version.strip().toLowerCase(Locale.ROOT);
        int plus = value.indexOf('+');
        if (plus >= 0) value = value.substring(0, plus);
        var items = new ArrayList<Object>();
        var token = new StringBuilder();
        Boolean digits = null;
        for (int i = 0; i <= value.length(); i++) {
            char c = i < value.length() ? value.charAt(i) : '.';
            boolean separator = c == '.' || c == '-' || c == '_';
            boolean digit = Character.isDigit(c);
            if (separator || (digits != null && digit != digits && !token.isEmpty())) {
                if (!token.isEmpty()) items.add(mavenItem(token.toString(), digits, i < value.length() ? value.charAt(i) : '.'));
                token.setLength(0);
                digits = null;
                if (separator) continue;
            }
            token.append(c);
            digits = digit;
        }
        // Trailing zeros and release qualifiers do not change the version ("1.0" == "1").
        while (!items.isEmpty()) {
            Object last = items.get(items.size() - 1);
            if ((last instanceof Long number && number == 0) || "".equals(last)) items.remove(items.size() - 1);
            else break;
        }
        return items;
    }

    private static Object mavenItem(String token, Boolean digits, char next) {
        if (Boolean.TRUE.equals(digits)) {
            try { return Long.parseLong(token.length() > 18 ? token.substring(0, 18) : token); }
            catch (NumberFormatException e) { return token; }
        }
        boolean followedByDigit = Character.isDigit(next);
        return switch (token) {
            case "a" -> followedByDigit ? "alpha" : token;
            case "b" -> followedByDigit ? "beta" : token;
            case "m" -> followedByDigit ? "milestone" : token;
            case "cr" -> "rc";
            case "ga", "final", "release" -> "";
            default -> token;
        };
    }

    private record Restriction(String lower, boolean lowerInclusive, String upper, boolean upperInclusive) implements Range {
        @Override public boolean test(String version) {
            if (lower != null) {
                int result = compareMaven(version, lower);
                if (result < 0 || (result == 0 && !lowerInclusive)) return false;
            }
            if (upper != null) {
                int result = compareMaven(version, upper);
                if (result > 0 || (result == 0 && !upperInclusive)) return false;
            }
            return true;
        }
        @Override public String display() {
            if (lower != null && lower.equals(upper)) return lower;
            var parts = new ArrayList<String>();
            if (lower != null) parts.add((lowerInclusive ? "≥ " : "> ") + lower);
            if (upper != null) parts.add((upperInclusive ? "≤ " : "< ") + upper);
            return String.join(", ", parts);
        }
    }

    /**
     * Maven version range. A bare version ("1.0") is Maven's soft requirement and matches anything,
     * as in Forge. Malformed ranges are treated as "any version" so they never produce false alarms.
     */
    public static Range maven(String spec) {
        if (spec == null) return ANY;
        String value = spec.strip();
        if (value.isEmpty() || value.equals("*") || !(value.startsWith("[") || value.startsWith("("))) return ANY;
        var restrictions = new ArrayList<Range>();
        int index = 0;
        while (index < value.length()) {
            char open = value.charAt(index);
            if (open == ',' || Character.isWhitespace(open)) { index++; continue; }
            if (open != '[' && open != '(') return ANY;
            int close = -1;
            for (int i = index + 1; i < value.length(); i++) {
                if (value.charAt(i) == ']' || value.charAt(i) == ')') { close = i; break; }
            }
            if (close < 0) return ANY;
            String inner = value.substring(index + 1, close);
            boolean lowerInclusive = open == '[', upperInclusive = value.charAt(close) == ']';
            int comma = inner.indexOf(',');
            if (comma < 0) {
                String exact = inner.strip();
                if (exact.isEmpty() || !lowerInclusive || !upperInclusive) return ANY;
                restrictions.add(new Restriction(exact, true, exact, true));
            } else {
                String lower = inner.substring(0, comma).strip(), upper = inner.substring(comma + 1).strip();
                restrictions.add(new Restriction(lower.isEmpty() ? null : lower, lowerInclusive, upper.isEmpty() ? null : upper, upperInclusive));
            }
            index = close + 1;
        }
        if (restrictions.isEmpty()) return ANY;
        return restrictions.size() == 1 ? restrictions.get(0) : new AnyOf(restrictions);
    }
}
