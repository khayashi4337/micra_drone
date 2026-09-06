package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnvironmentTest {

    @Test
    void aRootEnvironmentReadsBackWhatItWrote() {
        Environment env = new Environment();
        env.set("x", 1.0);
        assertEquals(1.0, env.get("x", 1));
    }

    @Test
    void readingAnUndefinedNameThrows() {
        Environment env = new Environment();
        MicraLangException e = assertThrows(MicraLangException.class, () -> env.get("missing", 3));
        assertEquals(3, e.line());
    }

    @Test
    void tryGetReturnsNullForAnUndefinedNameInsteadOfThrowing() {
        Environment env = new Environment();
        assertNull(env.tryGet("missing"));
    }

    @Test
    void aChildFrameFallsBackToTheParentForReads() {
        Environment parent = new Environment();
        parent.set("shared", "from parent");
        Environment child = new Environment(parent);
        assertEquals("from parent", child.get("shared", 1));
    }

    @Test
    void writingInAChildFrameDoesNotLeakIntoTheParent() {
        Environment parent = new Environment();
        parent.set("x", 1.0);
        Environment child = new Environment(parent);
        child.set("x", 2.0); // shadows, does not overwrite the parent's binding

        assertEquals(2.0, child.get("x", 1));
        assertEquals(1.0, parent.get("x", 1));
    }

    @Test
    void aChildFrameSeesLaterWritesToTheParent() {
        Environment parent = new Environment();
        Environment child = new Environment(parent);
        assertNull(child.tryGet("x"));

        parent.set("x", 5.0);
        assertEquals(5.0, child.get("x", 1));
    }
}
