package com.collabspace.authworkspace.adapter.in.rest.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// Base64+JSON mechanics shared by every keyset-paginated endpoint's cursor. Endpoints
// keep their own small typed wrapper (see WorkspaceCursor) that marshals named fields
// to/from the String map this class deals in -- only the field names/types vary per
// endpoint, never the encode/decode/error-wrapping mechanics themselves.
public final class CursorCodec {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private CursorCodec() {
	}

	public static Map<String, String> decode(String value) {
		try {
			byte[] decoded = Base64.getDecoder().decode(value);
			JsonNode node = OBJECT_MAPPER.readTree(decoded);
			Map<String, String> fields = new LinkedHashMap<>();
			node.fieldNames().forEachRemaining(name -> fields.put(name, node.get(name).asText()));
			if (fields.isEmpty()) {
				throw new IllegalArgumentException("Cursor decoded to an object with no fields");
			}
			return fields;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid cursor", ex);
		}
	}

	public static String encode(Map<String, String> fields) {
		ObjectNode node = OBJECT_MAPPER.createObjectNode();
		fields.forEach(node::put);
		return Base64.getEncoder().encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
	}

}
