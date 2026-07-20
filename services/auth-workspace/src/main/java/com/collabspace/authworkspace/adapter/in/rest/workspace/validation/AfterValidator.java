package com.collabspace.authworkspace.adapter.in.rest.workspace.validation;

import com.collabspace.authworkspace.adapter.in.rest.workspace.WorkspaceCursor;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AfterValidator implements ConstraintValidator<ValidAfter, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}

		try {
			WorkspaceCursor.decode(value);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
