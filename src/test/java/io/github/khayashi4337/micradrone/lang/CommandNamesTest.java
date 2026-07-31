package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CommandNamesTest {

    /**
     * Every name in CommandNames.ALL must be something Interpreter.evalCall actually recognizes -
     * calling each with zero arguments either succeeds or fails on argument count/type, but never
     * with "unknown function" (which would mean the two lists have drifted apart).
     */
    @Test
    void everyCommandNameIsRecognizedByTheInterpreter() {
        for (String name : CommandNames.ALL) {
            FakeDroneApi api = new FakeDroneApi(5);
            List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program =
                    new Parser(new Lexer(name + "()").scan()).parseProgram();
            try {
                new Interpreter(api).run(program);
            } catch (MicraLangException e) {
                assertFalse(e.getMessage().contains("unknown function"),
                        name + "() is in CommandNames.ALL but the interpreter doesn't recognize it: " + e.getMessage());
            }
        }
    }

    @Test
    void keywordsMatchesTheLanguagesReservedWords() {
        assertEquals(Set.of("if", "elif", "else", "while", "for", "in", "and", "or", "not", "True", "False", "None"),
                Lexer.keywords());
    }
}
