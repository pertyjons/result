# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-09-21

First public release, published to Maven Central under the group `io.github.pertyjons`:

- `result`: the sealed `Result<T, E>` type and `AsyncResult<T, E>`.
- `result-assertj`: AssertJ assertions for `Result`.
- `result-http`: a fluent, `Result`-returning adapter over `java.net.http.HttpClient`.
- `result-http-jackson`: a Jackson 3 `JsonCodec` for `result-http`.

The API is not yet stable and may change in any 0.x release.

[Unreleased]: https://github.com/pertyjons/result/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/pertyjons/result/releases/tag/v0.1.0
