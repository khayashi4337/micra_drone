package io.github.khayashi4337.micradrone.lang;

import java.util.List;

import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/**
 * A user-defined function value. Deliberately holds no captured environment - every call gets a
 * fresh frame parented at the interpreter's global scope, never the definition site's scope, so
 * this is not a closure (see docs/design/lang_def_return_break_continue.md for why).
 */
public record MicraFunction(String name, List<String> params, List<Stmt> body) {
}
