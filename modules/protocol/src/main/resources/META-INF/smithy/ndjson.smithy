$version: "2"

namespace org.polyvariant.ndjson

/// A REST protocol whose operations may stream their request body in, their
/// response body out, or both.
///
/// Metadata bindings (path / query / header) and non-streaming payloads follow
/// `alloy#simpleRestJson` exactly, so an operation without any `@streaming`
/// member behaves identically under either protocol — which is why the trait
/// list below is `simpleRestJson`'s, verbatim, plus `@streaming`. That list is
/// not a summary of the promise, it is the mechanism: the JSON hint mask is
/// derived from it, so a trait left out of it is ignored when a body is encoded
/// or decoded, silently and in both directions. On top of that, a
/// `@streaming` payload is framed by its shape — the same rule in both
/// directions, so an operation reads a body exactly the way a peer writes one:
///
/// - a `@streaming blob` is the body verbatim, as `application/octet-stream`;
/// - a `@streaming union` is one JSON value per line, as
///   `application/x-ndjson`;
/// - a `@streaming union` marked `@serverSentEvents` is one server-sent event
///   per value, as `text/event-stream`.
///
/// Smithy restricts `@streaming` to `:is(blob, union)`, so those cases are the
/// whole of it.
///
/// A streamed response commits its status before the first element is pulled,
/// so a mid-stream failure cannot be reported as an HTTP status — it has to be
/// carried as a member of the output union instead. Such unions are expected to
/// have a terminal member (e.g. `completed` / `failed`); the protocol does not
/// enforce this.
///
/// The protocol's constraints are all enforced by Smithy's own validators —
/// there is no custom validator here, because declaring the traits below is
/// what switches those on, and a `@traitValidators` rule restating any of them
/// would only double the error:
///
/// - `@streaming` is restricted to `:is(blob, union)` by the trait's own
///   selector, so the two framings above are the whole design space;
/// - a `@streaming` member must carry `@httpPayload` (`StreamingTrait`), and
///   every other member of that structure must then have an HTTP binding of its
///   own (`HttpPayload`) — otherwise it would have nowhere to travel, the body
///   being claimed by the stream;
/// - every operation must have `@http` (`HttpBindingsMissing`), since routing is
///   by method and URI.
///
/// Cross-cutting concerns are deliberately NOT part of this protocol —
/// authorization, tracing, metrics and error mapping alike. They are the
/// caller's, applied on top as an `Endpoint.Middleware`, so a service carrying
/// traits of that kind is free to interpret them itself.
@protocolDefinition(
    traits: [
        // Every trait `alloy#simpleRestJson` declares, because that is the promise made above: an
        // operation with no `@streaming` member must behave identically under either protocol, and
        // this list is exactly what makes that true. `hintMask` is derived from it, so a trait left
        // out here is silently ignored when a body is encoded or decoded — a missing
        // `@timestampFormat` doesn't fail loudly, it just writes an epoch number where the peer
        // expects a date-time string. Keep this in sync when alloy adds one.
        smithy.api#default
        smithy.api#error
        smithy.api#http
        smithy.api#httpError
        smithy.api#httpHeader
        smithy.api#httpLabel
        smithy.api#httpPayload
        smithy.api#httpPrefixHeaders
        smithy.api#httpQuery
        smithy.api#httpQueryParams
        smithy.api#httpResponseCode
        smithy.api#jsonName
        smithy.api#length
        smithy.api#pattern
        smithy.api#range
        smithy.api#required
        smithy.api#timestampFormat
        alloy#uuidFormat
        alloy#discriminated
        alloy#nullable
        alloy#untagged
        alloy#jsonUnknown
        alloy#openEnum
        alloy#durationSecondsFormat
        alloy#dateFormat
        alloy#localTimeFormat
        alloy#offsetDateTimeFormat
        alloy#preserveKeyOrder
        // The one addition: what this protocol has that `simpleRestJson` does not.
        smithy.api#streaming
    ]
)
@trait(selector: "service")
structure ndjsonRestJson {}

/// Frames a `@streaming union` as server-sent events (`text/event-stream`)
/// instead of NDJSON, for clients that only read that format.
///
/// Each value is the `data` of one event, encoded exactly as it would be on an
/// NDJSON line. Read back, `data` fields are joined by newlines until a blank
/// line ends the event; comments and the other fields (`event`, `id`, `retry`)
/// are skipped, and an event cut off before its blank line is not delivered.
///
/// Not part of `@protocolDefinition`'s trait list: that list decides which
/// traits the JSON codecs honour, and this one only decides the framing.
@trait(selector: "union [trait|streaming]")
structure serverSentEvents {
    /// The `data` of one more event, written after the last value, for clients
    /// that expect the stream to be closed that way, e.g. `[DONE]`. It is only
    /// written once the stream completes, so a stream cut short by a failure
    /// ends without it. Read back, an event with this `data` is skipped.
    terminator: String
}
