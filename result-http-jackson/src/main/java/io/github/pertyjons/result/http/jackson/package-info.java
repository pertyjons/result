/**
 * Jackson 3 implementation of {@link io.github.pertyjons.result.http.JsonCodec}. Adding this module
 * to the classpath registers {@link io.github.pertyjons.result.http.jackson.JacksonCodec} through
 * {@link java.util.ServiceLoader}, so {@code ResultHttpClient.DEFAULT} decodes JSON without further
 * configuration.
 */
@NullMarked
package io.github.pertyjons.result.http.jackson;

import org.jspecify.annotations.NullMarked;
