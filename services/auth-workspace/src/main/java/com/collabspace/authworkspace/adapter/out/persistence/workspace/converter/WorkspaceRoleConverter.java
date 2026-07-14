package com.collabspace.authworkspace.adapter.out.persistence.workspace.converter;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class WorkspaceRoleConverter implements AttributeConverter<WorkspaceRole, String> {

	@Override
	public String convertToDatabaseColumn(WorkspaceRole role) {
		if (role == null) {
			return null;
		}
		return role.getValue();
	}

	@Override
	public WorkspaceRole convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return WorkspaceRole.fromString(dbData);
	}

}
