package io.github.khayashi4337.micradrone.build.model;

import java.util.Optional;

/**
 * The common vocabulary of problems across every loop (design doc 05, section 4.1). Adding a code means
 * adding it to that table too; a test keeps the two in step. Only warnings and E-CLOG-RISK may be accepted
 * by the user (01, section 5).
 */
public enum IssueCode {
	E_SCHEMA("E-SCHEMA"),
	E_UNKNOWN_PART("E-UNKNOWN-PART"),
	E_PARAM_RANGE("E-PARAM-RANGE"),
	E_ANCHOR("E-ANCHOR"),
	E_OVERLAP("E-OVERLAP"),
	E_OUT_OF_BOUNDS("E-OUT-OF-BOUNDS"),
	E_NOT_SUPPORTED("E-NOT-SUPPORTED"),
	E_OPENING_NO_WALL("E-OPENING-NO-WALL"),
	E_ENCLOSURE_LEAK("E-ENCLOSURE-LEAK"),
	E_PORT_UNCONNECTED("E-PORT-UNCONNECTED"),
	E_PORT_MISMATCH("E-PORT-MISMATCH"),
	E_NO_ROUTE("E-NO-ROUTE"),
	E_ROT_CONFLICT("E-ROT-CONFLICT"),
	E_STRESS_OVER("E-STRESS-OVER"),
	E_POWER_NONE("E-POWER-NONE"),
	E_ITEM_DEADEND("E-ITEM-DEADEND"),
	E_CLOG_RISK("E-CLOG-RISK"),
	E_FLUID_LEAK("E-FLUID-LEAK"),
	E_SPACE_SHORT("E-SPACE-SHORT"),
	E_REGISTRY_VERSION("E-REGISTRY-VERSION"),
	E_SITE_BLOCKED("E-SITE-BLOCKED"),
	E_MATERIAL_SHORT("E-MATERIAL-SHORT"),
	E_MATERIAL_UNKNOWN("E-MATERIAL-UNKNOWN"),
	W_UNMODELED("W-UNMODELED"),
	W_STRESS_MARGIN("W-STRESS-MARGIN"),
	W_OVERSIZED_POWER("W-OVERSIZED-POWER"),
	W_NO_RECIPE("W-NO-RECIPE"),
	W_DECOR_COLLIDE("W-DECOR-COLLIDE"),
	E_BLOCK_FORBIDDEN("E-BLOCK-FORBIDDEN"),
	E_HEAT_NONE("E-HEAT-NONE"),
	W_FUEL_SUPPLY("W-FUEL-SUPPLY"),
	E_DOCK_MISALIGN("E-DOCK-MISALIGN"),
	E_ASSEMBLY_FAILED("E-ASSEMBLY-FAILED"),
	E_TEMPLATE_UNVERIFIED("E-TEMPLATE-UNVERIFIED"),
	E_SITE_CHANGED("E-SITE-CHANGED"),
	E_EFFECT_ESCAPES_CLAIM("E-EFFECT-ESCAPES-CLAIM"),
	E_TERRAFORM_UNCONFIRMED("E-TERRAFORM-UNCONFIRMED"),
	W_ASSEMBLY_AWAY("W-ASSEMBLY-AWAY"),
	W_NO_SETUP("W-NO-SETUP"),
	W_DYNAMIC_PART("W-DYNAMIC-PART"),
	E_PATCH_STALE("E-PATCH-STALE"),
	E_ID_INVALID("E-ID-INVALID"),
	E_ID_DUPLICATE("E-ID-DUPLICATE"),
	E_CONN_INVALID("E-CONN-INVALID"),
	E_SITE_MISSING("E-SITE-MISSING"),
	E_SCRIPT_FORBIDDEN("E-SCRIPT-FORBIDDEN"),
	E_SCRIPT_LIMIT("E-SCRIPT-LIMIT");

	private static final String WARNING_LABEL_PREFIX = "W-";
	private static final String ERROR_CLOG_RISK_LABEL = "E-CLOG-RISK";

	private final String label;

	IssueCode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public Severity severity() {
		return label.startsWith(WARNING_LABEL_PREFIX) ? Severity.WARN : Severity.ERROR;
	}

	/** Whether the user may accept the risk and go on: warnings and E-CLOG-RISK only. */
	public boolean acceptable() {
		return label.startsWith(WARNING_LABEL_PREFIX) || this == E_CLOG_RISK;
	}

	public static Optional<IssueCode> fromLabel(String label) {
		for (IssueCode code : values()) {
			if (code.label.equals(label)) {
				return Optional.of(code);
			}
		}
		return Optional.empty();
	}
}
