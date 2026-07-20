package com.collabspace.authworkspace.adapter.in.rest.common;

public record PaginationMetadata(boolean hasNextPage, String nextCursor, int limit, int count) {
}
