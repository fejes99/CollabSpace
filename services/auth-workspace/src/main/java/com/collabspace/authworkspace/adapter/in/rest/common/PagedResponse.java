package com.collabspace.authworkspace.adapter.in.rest.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PagedResponse<T>(@Schema(description = "Page of items") List<T> data,
		@Schema(description = "Pagination metadata") PaginationMetadata pagination) {
}
