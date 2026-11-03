package net.ptcrys.fpsmatch.editor.api.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import org.junit.jupiter.api.Test;

class ExtensionRegistryDiscardTest {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId OWNER = EditorId.parse("thirdparty:module");
    private static final EditorId ID = EditorId.parse("thirdparty:increment");
    private static final ExtensionKind<IntUnaryOperator> KIND = new ExtensionKind<>(
            EditorId.parse("thirdparty:operation"), IntUnaryOperator.class);

    @Test
    void discardBeforeFreezePermanentlyRevokesEveryCollectionOperation() {
        ExtensionRegistryBuilder builder = populated(operand -> operand + 1);
        builder.discard();
        builder.discard();
        assertClosed(builder);
    }

    @Test
    void discardAfterFreezeDoesNotRevokeThePublishedImplementationOrMetadata() {
        IntUnaryOperator implementation = operand -> operand + 1;
        ExtensionRegistryBuilder builder = populated(implementation);
        ExtensionRegistry registry = builder.freeze();
        builder.discard();
        builder.discard();
        assertClosed(builder);
        assertSame(implementation, registry.find(KIND, ID).orElseThrow());
        assertEquals(42, registry.find(KIND, ID).orElseThrow().applyAsInt(41));
        assertEquals("2.7.13", registry.metadata().modules().get(OWNER).buildVersion());
        assertEquals(OWNER, registry.metadata().contributions().get(key()).owner());
        assertThrows(UnsupportedOperationException.class, () -> registry.entries(KIND).clear());
    }

    @Test
    void discardEmptyBuilderCannotBeUsedToProduceAnEmptySuccessSnapshot() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.discard();
        assertClosed(builder);
    }

    private static ExtensionRegistryBuilder populated(IntUnaryOperator implementation) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(KIND);
        builder.addModule(module());
        builder.add(KIND, contribution(), implementation);
        return builder;
    }

    private static void assertClosed(ExtensionRegistryBuilder builder) {
        assertThrows(IllegalStateException.class, () -> builder.define(KIND));
        assertThrows(IllegalStateException.class, () -> builder.addModule(module()));
        assertThrows(IllegalStateException.class, () -> builder.add(KIND, contribution(), operand -> operand));
        assertThrows(IllegalStateException.class, builder::freeze);
    }

    private static ContributionKey key() {
        return new ContributionKey(KIND.id(), ID);
    }

    private static ModuleSpec module() {
        return new ModuleSpec(OWNER, "2.7.13", API, Set.of(), Set.of());
    }

    private static Contribution contribution() {
        return new Contribution(key(), OWNER, Contribution.Side.COMMON, Set.of());
    }
}
