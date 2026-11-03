package example.extensions;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;

/**
 * An independent public-API consumer of the extension registry: it only uses
 * {@code net.ptcrys.fpsmatch.editor.core.api}, {@code net.ptcrys.fpsmatch.editor.core.registry} and the three public types of
 * {@code editor-extensions}, exactly as a third-party module would.
 *
 * <p>It declares two extension kinds of its own, publishes real implementations, then looks them up
 * from the frozen registry and actually invokes them. This is a binding smoke test, not a claim that
 * a document/form/panel/save integration already exists: those seams stay with the owner of each
 * extension point.</p>
 *
 * <pre>
 * javac -cp editor-extensions.jar+editor-registry.jar+editor-api.jar examples/RegisteredExtensionsSmoke.java
 * java  -cp <same classpath> example.extensions.RegisteredExtensionsSmoke
 * </pre>
 *
 * <p>Exit code {@code 0} means every check passed; any deviation throws and exits non-zero.</p>
 */
public final class RegisteredExtensionsSmoke {

    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final Contribution.Side COMMON = Contribution.Side.COMMON;
    private static final Contribution.Side CLIENT = Contribution.Side.CLIENT;

    private static final EditorId CORE = EditorId.parse("example:core");
    private static final EditorId EXTRAS = EditorId.parse("example:extras");

    private static final EditorId SCORER_KIND_ID = EditorId.parse("example:scorer");
    private static final EditorId LABEL_KIND_ID = EditorId.parse("example:label");

    private static final ExtensionKind<IntUnaryOperator> SCORER =
            kind(SCORER_KIND_ID, IntUnaryOperator.class);
    private static final ExtensionKind<UnaryOperator<String>> LABEL =
            kind(LABEL_KIND_ID, UnaryOperator.class);

    private static final EditorId DOUBLE = EditorId.parse("example:double");
    private static final EditorId SHOUT = EditorId.parse("example:shout");
    private static final EditorId SHARED = EditorId.parse("example:shared");
    private static final EditorId DUPLICATE = EditorId.parse("example:duplicate");
    private static final EditorId CLIENT_ONLY = EditorId.parse("example:client_only");

    private RegisteredExtensionsSmoke() {
    }

    public static void main(String[] args) {
        ExtensionRegistry registry = publish();
        lookUpAndRun(registry);
        oneIdInTwoKindsCoexist(registry);
        undefinedKindIsRejected(registry);
        duplicateIsNotPublished();
        clientImplementationStaysOffTheCommonRegistry();
        System.out.println("RegisteredExtensionsSmoke: all checks passed");
    }

    /** Builds one registry with two third-party modules and four usable contributions. */
    private static ExtensionRegistry publish() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(SCORER);
        builder.define(LABEL);
        builder.addModule(module(CORE));
        builder.addModule(module(EXTRAS));
        builder.add(SCORER, contribution(SCORER_KIND_ID, DOUBLE), operand -> operand * 2);
        builder.add(LABEL, contribution(LABEL_KIND_ID, SHOUT), text -> text.toUpperCase(Locale.ROOT) + "!");
        builder.add(SCORER, contribution(SCORER_KIND_ID, SHARED, EXTRAS, COMMON,
                new ContributionKey(SCORER_KIND_ID, DOUBLE)), operand -> operand + 1);
        builder.add(LABEL, contribution(LABEL_KIND_ID, SHARED), text -> "[" + text + "]");
        ExtensionRegistry registry = builder.freeze();
        check(registry.metadata().problems().isEmpty(), "publishing produced problems: " + registry.metadata().problems());
        check(registry.metadata().modules().keySet().equals(Set.of(CORE, EXTRAS)), "unexpected modules");
        return registry;
    }

    /** The point of the whole exercise: the frozen lookup hands back callable implementations. */
    private static void lookUpAndRun(ExtensionRegistry registry) {
        check(42 == registry.find(SCORER, DOUBLE).orElseThrow().applyAsInt(21), "find(SCORER) did not run");
        check("HELLO!".equals(registry.find(LABEL, SHOUT).orElseThrow().apply("hello")), "find(LABEL) did not run");

        Map<EditorId, IntUnaryOperator> scorers = registry.entries(SCORER);
        Map<EditorId, UnaryOperator<String>> labels = registry.entries(LABEL);
        check(scorers.keySet().equals(Set.of(DOUBLE, SHARED)), "entries(SCORER)=" + scorers.keySet());
        check(labels.keySet().equals(Set.of(SHOUT, SHARED)), "entries(LABEL)=" + labels.keySet());
        check(8 == scorers.get(DOUBLE).applyAsInt(4), "double implementation did not run");
        check(6 == scorers.get(SHARED).applyAsInt(5), "depending implementation did not run");
        check("HI!".equals(labels.get(SHOUT).apply("hi")), "shout implementation did not run");
        System.out.println("lookup/run: " + scorers.size() + " scorer(s), " + labels.size() + " label(s)");
    }

    /** The same contribution id under two kinds is two independent contributions. */
    private static void oneIdInTwoKindsCoexist(ExtensionRegistry registry) {
        check(202 == registry.find(SCORER, SHARED).orElseThrow().applyAsInt(201), "shared id lost its scorer");
        check("[x]".equals(registry.find(LABEL, SHARED).orElseThrow().apply("x")), "shared id lost its label");
        check(registry.entries(SCORER).containsKey(SHARED), "shared id missing from the scorer kind");
        check(registry.entries(LABEL).containsKey(SHARED), "shared id missing from the label kind");
        System.out.println("shared id: one id, two kinds, both real implementations usable");
    }

    /** Asking about an extension point nobody defined is a contract error, not an empty result. */
    private static void undefinedKindIsRejected(ExtensionRegistry registry) {
        ExtensionKind<String> neverDefined = kind(EditorId.parse("example:never_defined"), String.class);
        try {
            String found = registry.find(neverDefined, DOUBLE).orElseThrow();
            throw new AssertionError("undefined kind was accepted: " + found);
        } catch (IllegalArgumentException expected) {
            System.out.println("undefined kind rejected: " + expected.getMessage());
        }
    }

    /** A duplicated contribution makes both records and their dependents unavailable. */
    private static void duplicateIsNotPublished() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(SCORER);
        builder.addModule(module(CORE));
        ContributionKey duplicate = new ContributionKey(SCORER_KIND_ID, DUPLICATE);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DUPLICATE), operand -> operand * 2);
        builder.add(SCORER, contribution(SCORER_KIND_ID, DUPLICATE), operand -> operand * 3);
        builder.add(SCORER, contribution(SCORER_KIND_ID, EditorId.parse("example:dependent"), CORE, COMMON,
                duplicate), operand -> operand + 100);
        ExtensionRegistry duplicated = builder.freeze();
        check(duplicated.find(SCORER, DUPLICATE).isEmpty(), "a duplicated implementation was published");
        check(duplicated.entries(SCORER).isEmpty(), "a dependent of a duplicate was published");
        System.out.println("duplicate records: " + duplicated.metadata().problems().size() + " diagnostic(s), 0 published");
    }

    /** A COMMON registry never exposes a CLIENT implementation. */
    private static void clientImplementationStaysOffTheCommonRegistry() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, COMMON);
        builder.define(SCORER);
        builder.addModule(module(CORE));
        builder.add(SCORER, contribution(SCORER_KIND_ID, CLIENT_ONLY, CORE, CLIENT), operand -> operand * 3);
        ExtensionRegistry common = builder.freeze();
        check(common.find(SCORER, CLIENT_ONLY).isEmpty(), "client implementation reached a COMMON registry");

        ExtensionRegistryBuilder clientBuilder = new ExtensionRegistryBuilder(API, CLIENT);
        clientBuilder.define(SCORER);
        clientBuilder.addModule(module(CORE));
        clientBuilder.add(SCORER, contribution(SCORER_KIND_ID, CLIENT_ONLY, CORE, CLIENT), operand -> operand * 3);
        check(9 == clientBuilder.freeze().find(SCORER, CLIENT_ONLY).orElseThrow().applyAsInt(3),
                "client implementation did not run on the client");
        System.out.println("side filtering: CLIENT implementation only on the CLIENT registry");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ExtensionKind<T> kind(EditorId id, Class<?> valueType) {
        return new ExtensionKind<>(id, (Class<T>) valueType);
    }

    private static ModuleSpec module(EditorId id) {
        return new ModuleSpec(id, "1.0.0", API, Set.of(), Set.of());
    }

    private static Contribution contribution(EditorId kindId, EditorId id) {
        return contribution(kindId, id, CORE, COMMON);
    }

    private static Contribution contribution(EditorId kindId, EditorId id, EditorId owner,
                                             Contribution.Side side, ContributionKey... required) {
        return new Contribution(new ContributionKey(kindId, id), owner, side, Set.of(required));
    }
}
