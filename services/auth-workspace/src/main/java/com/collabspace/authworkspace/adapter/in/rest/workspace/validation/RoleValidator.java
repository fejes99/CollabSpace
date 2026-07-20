package com.collabspace.authworkspace.adapter.in.rest.workspace.validation;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class RoleValidator implements ConstraintValidator<ValidRole, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}

		try {
			WorkspaceRole.fromString(value);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
