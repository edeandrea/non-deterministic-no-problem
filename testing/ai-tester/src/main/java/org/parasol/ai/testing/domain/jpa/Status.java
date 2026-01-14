package org.parasol.ai.testing.domain.jpa;

public enum Status {
	SUCCESS, FAILURE, UNKNOWN;

	public static Status getDefault() {
		return UNKNOWN;
	}
}
