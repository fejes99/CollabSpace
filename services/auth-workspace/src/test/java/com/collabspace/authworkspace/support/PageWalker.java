package com.collabspace.authworkspace.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Walks a cursor-paginated endpoint's PagedResponse via nextCursor until exhausted.
// The HTTP call itself (URL, headers, request params) stays with the caller via
// PageFetcher -- only the walking/budget/termination logic is generic across every
// paginated endpoint.
public final class PageWalker {

	private PageWalker() {
	}

	public interface PageFetcher {

		Page fetch(int limit, String after) throws Exception;

	}

	public record Page(List<Map<String, Object>> data, boolean hasNextPage, String nextCursor) {
	}

	public static List<Map<String, Object>> walkAll(PageFetcher fetcher, int limit) throws Exception {
		List<Map<String, Object>> all = new ArrayList<>();
		String after = null;
		boolean hasNextPage = true;
		// Budget scales with limit, in total rows rather than fixed iteration count --
		// otherwise a small limit (used to force real multi-page traversal in a test)
		// gets a much smaller pollution tolerance than a near-default limit walking the
		// same table.
		int safetyCap = Math.max(50, 5000 / limit);
		while (hasNextPage && safetyCap-- > 0) {
			Page page = fetcher.fetch(limit, after);
			all.addAll(page.data());
			hasNextPage = page.hasNextPage();
			if (hasNextPage) {
				after = page.nextCursor();
			}
		}
		// Fail loudly and specifically if the cap was the reason the loop stopped,
		// rather than letting callers see a confusing "id not found" downstream.
		if (hasNextPage) {
			throw new IllegalStateException("PageWalker exhausted its page-fetch budget with hasNextPage still true -- "
					+ "either the shared test database has grown very large, or pagination is broken");
		}
		return all;
	}

	public static int indexOfId(List<Map<String, Object>> data, String id) {
		for (int i = 0; i < data.size(); i++) {
			if (id.equals(data.get(i).get("id"))) {
				return i;
			}
		}
		return -1;
	}

}
