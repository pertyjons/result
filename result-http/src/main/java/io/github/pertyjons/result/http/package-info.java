/**
 * A fluent, {@link io.github.pertyjons.result.Result}-returning adapter over {@link
 * java.net.http.HttpClient}. The entry point is {@link
 * io.github.pertyjons.result.http.ResultHttpClient}; failures are modelled by the sealed {@link
 * io.github.pertyjons.result.http.HttpError}.
 *
 * <p>The package is {@link org.jspecify.annotations.NullMarked} and its API accepts and returns no
 * null: every parameter and return type is non-null, apart from the argument of {@code equals}. A
 * value that may be absent is an {@link java.util.Optional}, an ignored body is {@link
 * io.github.pertyjons.result.Result.Unit}, and a failed outcome is an {@code Err}.
 */
@NullMarked
package io.github.pertyjons.result.http;

import org.jspecify.annotations.NullMarked;
