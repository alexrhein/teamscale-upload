package com.teamscale.upload.client;

import java.io.IOException;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Wraps a {@link okhttp3.Response} but makes it always safe to access the
 * response body.
 *
 * OkHttp will not buffer the response body stream, so if you read it once, you
 * cannot read it again. This makes it necessary for us to buffer the string
 * ourselves so it can be read multiple times, e.g. by error-handling code.
 */
public class SafeResponse {

	/**
	 * The unsafe response. Use this for everything except accessing the
	 * {@link #body}
	 */
	public final Response unsafeResponse;

	/** A safe-to-use body string. This is never null. */
	public final String body;

	public SafeResponse(Response unsafeResponse) {
		this.unsafeResponse = unsafeResponse;
		this.body = readBodySafe(unsafeResponse);
	}

	/**
	 * Either returns the body of the response or if that cannot be read, a safe
	 * fallback string.
	 */
	private static String readBodySafe(Response response) {
		try {
			ResponseBody body = response.body();
			if (body == null) {
				return "<no response body>";
			}
			return body.string();
		} catch (IOException e) {
			e.printStackTrace();
			return "Failed to read response body: " + e.getMessage();
		}
	}

	@Override
	public String toString() {
		// we do not want to include the potentially huge body here
		return unsafeResponse.toString();
	}
}
