/**
 * A Rust-inspired {@link io.github.pertyjons.result.Result} type for explicit, typed error
 * handling. {@link io.github.pertyjons.result.AsyncResult} provides non-blocking composition of
 * asynchronous Results through {@link java.util.concurrent.CompletionStage}.
 *
 * <p>The package is {@link org.jspecify.annotations.NullMarked}: every type parameter and every
 * parameter/return type is non-null. {@code Result<T, E>} never holds a null value or error, and no
 * accessor on it returns null; an absent side is an empty {@link java.util.Optional}. The only
 * {@code @Nullable} in the public API is the input to {@code Result.ofNullable}, which exists to
 * turn a null from a legacy API into an {@code Err} at the boundary.
 */
@NullMarked
package io.github.pertyjons.result;

import org.jspecify.annotations.NullMarked;
