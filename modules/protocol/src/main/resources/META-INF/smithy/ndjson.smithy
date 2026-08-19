$version: "2"

namespace org.polyvariant.ndjson

/// A REST protocol whose operations may stream a raw binary request body in,
/// and/or a sequence of newline-delimited JSON values out.
///
/// Metadata bindings (path / query / header) and non-streaming payloads follow
/// `alloy#simpleRestJson` exactly, so an operation without any `@streaming`
/// member behaves identically under either protocol. On top of that:
///
/// - An operation whose input payload is a `@streaming` blob receives the raw
///   request body as a byte stream, rather than a decoded value.
/// - An operation whose output payload is a `@streaming` union writes one JSON
///   value per line as `application/x-ndjson`. The response status is sent as
///   soon as the stream begins, so a mid-stream failure cannot be reported as
///   an HTTP status — it is carried as a member of the output union instead.
///   Such unions are expected to have a terminal member (e.g. `completed` /
///   `failed`); the protocol does not enforce this.
///
/// Cross-cutting concerns are deliberately NOT part of this protocol —
/// authorization, tracing, metrics and error mapping alike. They are the
/// caller's, applied on top as an `Endpoint.Middleware`, so a service carrying
/// traits of that kind is free to interpret them itself.
@protocolDefinition(
    traits: [
        smithy.api#http
        smithy.api#httpPayload
        smithy.api#httpLabel
        smithy.api#httpQuery
        smithy.api#httpQueryParams
        smithy.api#httpHeader
        smithy.api#httpPrefixHeaders
        smithy.api#httpError
        smithy.api#error
        smithy.api#streaming
        smithy.api#default
        smithy.api#required
    ]
)
@trait(selector: "service")
structure ndjsonRestJson {}
