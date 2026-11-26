$version: "2"

// Compliance tests for `org.polyvariant.ndjson#ndjsonRestJson`.
//
// Every case is an ordinary `smithy.test` trait, read the standard way:
// `@httpRequestTests` / `@httpResponseTests` for operations whose body is not
// a stream of values (including `@streaming blob`s, whose body is the bytes
// verbatim), and `@eventStreamTests` for operations that stream a union.
//
// `@eventStreamTests` was written for AWS's binary event streams, so here is
// how its fields map onto this protocol's framings:
//
// - An event is one NDJSON line, or one server-sent event.
// - A *modeled* event (one with `params`) is one value of the union. Its
//   `body` is the value's JSON, exactly what `simpleRestJson` would write as
//   a whole body, and is compared as JSON.
// - An *unmodeled* event (no `params`) carries no value. On the way out it
//   is a frame the implementation must still write — the `@serverSentEvents`
//   terminator, whose `data` is the `body`. On the way in it is bytes the
//   reader must skip: a blank line, a comment, an event without data.
// - `bytes`, when present, is the exact framed event, and only matters when
//   reading. When absent, the bytes are the `body` framed as the protocol
//   writes it: `body` + `\n` for NDJSON, `data: ` + `body` + `\n\n` for
//   server-sent events. A case that only makes sense in one direction says so
//   with `appliesTo`: the client writes `request` events and reads `response`
//   ones, the server the other way around.
// - `expectation: { failure: {} }` means the stream must fail. A server reads
//   the whole request stream before it responds, so for it that is a 400,
//   which `initialResponse` states.
// - `initialRequest` / `initialResponse` are the HTTP message around the
//   stream: method, URI, headers and, for a side that doesn't stream, its
//   JSON body. Every case states both, so an implementation never has to
//   derive the message it sends or answers with: a server is sent
//   `initialRequest` with the `request` events as its body, and a client is
//   answered with `initialResponse` and the `response` events.
// - `initialRequestParams` / `initialResponseParams` are the operation's
//   input and output without the streamed member; absent means `{}`.
// - `params` are in Smithy's node form, with one wrinkle Smithy's own
//   validators impose: a timestamp is epoch seconds in `@httpRequestTests` /
//   `@httpResponseTests`, but in its member's `@timestampFormat` in
//   `@eventStreamTests` (epoch seconds when it has none). A blob is its text.
//
// A streamed union member is never `@required` here: `@eventStreamTests`
// checks `initialRequestParams` / `initialResponseParams` against the whole
// input / output, and the stream's values are in `events` instead.
//
// Each implementation runs the side it implements: a server decodes `request`
// events and encodes `response` ones, a client does the opposite.
namespace org.polyvariant.ndjson.compliance

use org.polyvariant.ndjson#ndjsonRestJson

@ndjsonRestJson
service NdjsonRestJsonCompliance {
    operations: [
        UnaryBindings
        UnaryQueryParams
        UnaryPayload
        PutBlob
        GetBlob
        EchoCsv
        Subscribe
        Publish
        EchoRecords
        Watch
        Collect
        Listen
        Emit
    ]
}

list StringList {
    member: String
}

map StringMap {
    key: String
    value: String
}
