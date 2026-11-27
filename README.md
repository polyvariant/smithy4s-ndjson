# smithy4s-ndjson

A [Smithy](https://smithy.io) protocol for services that stream — raw bytes or
[newline-delimited JSON](https://github.com/ndjson/ndjson-spec), in either direction — plus its
[smithy4s](https://disneystreaming.github.io/smithy4s/) http4s interpreter.

It's the shape smithy4s's `SimpleRestJsonBuilder` can't express: `routes` there takes a kind-1
algebra, which erases the `@streaming` members an operation needs to reach the request and response
bodies.

## Installation

Every artifact needs Java 17 or newer, from 0.5.0 on.

```scala
libraryDependencies += "org.polyvariant" %% "smithy4s-ndjson-http4s" % "0.5.0"
```

The protocol trait is published separately, as a plain Java artifact with no Scala suffix — so
`smithy-build`, the Smithy CLI, or codegen for another language can depend on the protocol alone:

```scala
libraryDependencies += "org.polyvariant" % "smithy4s-ndjson-protocol" % "0.5.0"
```

Scala users don't need it explicitly. `smithy4s-ndjson-http4s` depends on `smithy4s-ndjson-core`,
which carries the generated Scala for the trait and records the protocol as a `smithy4sDependencies`
entry in its jar manifest — so smithy4s's build plugins pull the protocol onto your codegen model
path on their own, and the trait resolves off the classpath via `META-INF/smithy`.

## The protocol

```smithy
$version: "2"

namespace example

use org.polyvariant.ndjson#ndjsonRestJson

@ndjsonRestJson
service ImportService {
    operations: [Import]
}

@http(method: "POST", uri: "/import/{tag}", code: 202)
operation Import {
    input := {
        @httpLabel
        @required
        tag: String

        @httpPayload
        @required
        body: Payload
    }

    output := {
        @httpPayload
        @required
        event: Event
    }
}

@streaming
blob Payload

@streaming
union Event {
    progress: Progress
    completed: Completed
    failed: Failed
}
```

Metadata bindings (path / query / header) and non-streaming payloads follow `alloy#simpleRestJson`
exactly, so an operation without any `@streaming` member behaves identically under either protocol —
a service may freely mix both kinds.

That equivalence is why `@protocolDefinition` lists `simpleRestJson`'s traits verbatim, plus
`@streaming`. The list isn't documentation: the JSON hint mask is derived from it, so a trait missing
from it is ignored when a body is encoded or decoded — a dropped `@timestampFormat` writes an epoch
number where a peer expects an RFC-3339 string, with no error on either side. A test asserts the two
lists against each other, so alloy adding a trait fails the build rather than drifting quietly.

On top of that, a `@streaming` payload is framed by its *shape*, and the same rule applies to both
edges — so an operation reads a body exactly the way a peer writes one:

|                                        | input                         | output                                                            |
| -------------------------------------- | ----------------------------- | ----------------------------------------------------------------- |
| `@streaming blob`                      | raw bytes in                  | raw bytes out (its `@mediaType`, else `application/octet-stream`) |
| `@streaming union`                     | NDJSON decoded in             | NDJSON encoded out (`application/x-ndjson`)                       |
| `@streaming union` `@serverSentEvents` | server-sent events decoded in | server-sent events encoded out (`text/event-stream`)              |

A blob's `@mediaType` only labels the bytes — a `@streaming` blob with `@mediaType("text/csv")` goes
out as `text/csv` — it never changes how they are framed.

Smithy restricts `@streaming` to `:is(blob, union)`, so a union is framed as NDJSON unless it opts
into [server-sent events](#server-sent-events). The example above uses one of each; an operation is
free to use the same framing on both sides:

```smithy
/// Raw bytes in, raw bytes out.
@http(method: "POST", uri: "/relay", code: 200)
operation Relay {
    input := {
        @httpPayload
        @required
        body: Payload
    }

    output := {
        @httpPayload
        @required
        content: Payload
    }
}
```

Each NDJSON line is byte-for-byte what `simpleRestJson` would have written as a whole body, and is
newline-*terminated* rather than separated — so a reader that hits EOF mid-line knows it was
truncated. Reading a stream back skips blank lines, so that trailing terminator doesn't come back as
an extra element on the next hop; a line that is present but malformed fails the request rather than
silently shortening the stream — with a 400 if the operation reads it before responding.

### Server-sent events

A client that only reads [server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
— a browser's `EventSource`, or a chat UI built on one — is served by marking the union
`@serverSentEvents`:

```smithy
use org.polyvariant.ndjson#serverSentEvents

@streaming
@serverSentEvents(terminator: "[DONE]")
union ChatEvent {
    delta: TextDelta
    finished: Finished
}
```

Each value is the `data` of one event, encoded exactly as it would be on an NDJSON line, and the
body is `text/event-stream`. The optional `terminator` is the `data` of one more event written after
the last value, for clients that expect the stream to be closed that way. It is only written once
the stream completes, so a stream cut short by a failure ends without it, and the client can tell
it is incomplete. Read back, `data` fields are joined by newlines until a blank line ends the event;
comments and the other fields (`event`, `id`, `retry`) are skipped, an event cut off before its
blank line is not delivered, an event with the terminator as its `data` is dropped, and data that
doesn't decode fails the request, as a malformed NDJSON line does.

### What the model must satisfy

There is no custom validator in this protocol. Everything it relies on is already enforced by
Smithy's own validators, switched on by the traits listed in `@protocolDefinition`:

- `@streaming` is restricted to `:is(blob, union)` by the trait's own selector;
- a `@streaming` member must carry `@httpPayload` (`StreamingTrait`);
- every *other* member of that structure must then have an HTTP binding of its own (`HttpPayload`) —
  otherwise it would have nowhere to travel, the body being claimed by the stream;
- every operation must have `@http` (`HttpBindingsMissing`), since routing is by method and URI.

So a metadata-bound member alongside a streamed body is fine, and a member with no binding at all is
a build error rather than a value that silently vanishes.

## Serving it

`NdjsonRestJsonBuilder` is the counterpart to `SimpleRestJsonBuilder`, differing in exactly one
respect: it takes a kind-5 algebra (`WithStreamedIO`), so an operation reads as
`Stream[F, SI] => F[(O, Stream[F, SO])]`.

```scala
import org.polyvariant.ndjson.http4s.*

val impl: ImportServiceGen[WithStreamedIO[IO]] =
  new ImportServiceGen[WithStreamedIO[IO]] {
    def `import`(tag: String) =
      body =>
        IO.pure(
          (
            ImportOutput(),
            body.through(ingest).map(n => Event.ProgressCase(Progress(n))),
          )
        )
  }

val routes: HttpRoutes[IO] = NdjsonRestJsonBuilder.routes(impl)
```

The `F[...]` sits outside the tuple deliberately: it's the effect of *starting* the operation —
validating input, opening resources, producing the envelope — and it completes before the response
stream is drained.

### Two things streaming changes

**A late failure can't be an HTTP status.** The status is committed when the stream begins, so a
mid-stream failure has to travel as a member of the output union (`failed`, by convention). Errors
raised *before* streaming starts are still encoded normally: a declared error with the status from
its `@httpError`, and a request that fails to decode with a 400, exactly as under `simpleRestJson`.

**Request-scoped state is gone by the time the stream runs.** The body is drained after the handler
returns, so an `IOLocal` set by middleware — the caller's identity, a tracing span — has already
gone out of scope. Resolve anything request-scoped in the `F[...]`, never lazily from inside the
stream: reading it late doesn't fail, it silently observes whatever the local was reset to. This is
inherent to streaming a response rather than a quirk of this interpreter, and it's pinned down by
`MiddlewareScopeTests`.

## Middleware

Cross-cutting concerns are deliberately not part of the protocol. Tracing, metrics, error mapping,
authorization, rate limiting — all of it is applied on top as a `ServerEndpointMiddleware[F]`, the
same type `SimpleRestJsonBuilder` takes, so middleware written for one builder works unchanged with
the other:

```scala
NdjsonRestJsonBuilder.routes(impl, myMiddleware)
```

Middleware can read the endpoint's own hints, so a trait carried by an operation in the model is
available to interpret however the caller likes.

It wraps the handler of an endpoint that has *already matched*, never the routing decision itself,
so a request for an unknown path falls through untouched and these routes compose with others.
Note the scope caveat above: middleware built on request-scoped state sees that state while the
operation starts, but not while the response stream is drained.

## OpenAPI

smithy4s generates an OpenAPI spec for each service at build time — through alloy, which only knows
`alloy#simpleRestJson`. `smithy4s-ndjson-openapi` teaches that conversion this protocol: put it on
the codegen model path, and every `@ndjsonRestJson` service gets a spec too, which
`smithy4s-http4s-swagger` serves like any other.

```scala
libraryDependencies += "org.polyvariant" % "smithy4s-ndjson-openapi" % "0.5.0" % Smithy4s
```

Everything that doesn't stream is described exactly as alloy describes `simpleRestJson`. A
`@streaming` payload is described by its framing:

|                                        | media type                                        | schema                        |
| -------------------------------------- | ------------------------------------------------- | ----------------------------- |
| `@streaming blob`                      | its `@mediaType`, else `application/octet-stream` | a binary string               |
| `@streaming union`                     | `application/x-ndjson`                            | the union: one line's worth   |
| `@streaming union` `@serverSentEvents` | `text/event-stream`                               | the union: one event's `data` |

Like the protocol, it's a plain Java artifact with no Scala suffix — note the single `%`. It runs
inside the code generator, on whichever Scala version that uses (sbt 1 and sbt 2 already differ),
so it calls alloy only through Java signatures, which every Scala build of alloy shares.

## Compliance tests

`smithy4s-ndjson-protocol-tests` describes the protocol's wire format as Smithy
[protocol compliance tests](https://smithy.io/2.0/additional-specs/http-protocol-compliance-tests.html),
so an implementation in any language can check itself against it — the way `alloy-protocol-tests`
does for `simpleRestJson`:

```scala
libraryDependencies += "org.polyvariant" % "smithy4s-ndjson-protocol-tests" % "0.5.0"
```

It is a model of one `@ndjsonRestJson` service whose operations carry the cases, and nothing else:

- `@httpRequestTests` / `@httpResponseTests` for operations that don't stream (a spot check of the
  `simpleRestJson` promise: every metadata binding, a structure payload, a declared error), and for
  `@streaming blob`s, whose body is the bytes verbatim;
- `@eventStreamTests` for `@streaming union`s, NDJSON and server-sent events alike: the framing in
  both directions, what a reader must forgive (blank lines, `\r\n`, comments, other event fields, the
  terminator) and what it must reject, constraint traits on streamed values, and one case per trait
  in the protocol's `@protocolDefinition` list that changes how a streamed value is encoded.

`@eventStreamTests` was written for AWS's binary event streams; how its fields map onto these
framings is documented at the top of the model (`service.smithy`). Every case states its whole HTTP
request and response, so a harness never has to derive either.

This repository runs the server side of every case against `NdjsonRestJsonBuilder`, in
`modules/compliance-tests`, partly through smithy4s's own compliance harness. smithy4s can't generate
code for a model carrying `@eventStreamTests` — it can't render an event's `Blob`s as a hint — so
codegen strips them with [smithy-transformations](https://github.com/polyvariant/smithy-transformations)'
`removeTraits`, and the tests read those cases from the model instead.

## Platform support

JVM only for now. Nothing in the interpreter is JVM-specific, so JS and Native are open — the http4s
and smithy4s dependencies already cross-build.

## License

Apache 2.0.
