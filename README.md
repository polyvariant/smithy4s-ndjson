# smithy4s-ndjson

A [Smithy](https://smithy.io) protocol for services that stream — a raw binary request body in,
and/or [newline-delimited JSON](https://github.com/ndjson/ndjson-spec) out — plus its
[smithy4s](https://disneystreaming.github.io/smithy4s/) http4s interpreter.

It's the shape smithy4s's `SimpleRestJsonBuilder` can't express: `routes` there takes a kind-1
algebra, which erases the `@streaming` members an operation needs to reach the request and response
bodies.

## Installation

```scala
libraryDependencies += "org.polyvariant" %% "smithy4s-ndjson-http4s" % "0.1.0"
```

The protocol trait is published separately, as a plain Java artifact with no Scala suffix — so
`smithy-build`, the Smithy CLI, or codegen for another language can depend on the protocol alone:

```scala
libraryDependencies += "org.polyvariant" % "smithy4s-ndjson-protocol" % "0.1.0"
```

Scala users don't need it explicitly: `smithy4s-ndjson-http4s` brings it along, and the trait
resolves off the classpath via `META-INF/smithy`.

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
a service may freely mix both kinds. On top of that:

- an operation whose input payload is a `@streaming` blob receives the raw request body as a byte
  stream, rather than a decoded value;
- an operation whose output payload is a `@streaming` union writes one JSON value per line as
  `application/x-ndjson`.

Each line is byte-for-byte what `simpleRestJson` would have written as a whole body, and is
newline-*terminated* rather than separated — so a reader that hits EOF mid-line knows it was
truncated.

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
raised *before* streaming starts are still encoded normally, with the status from their `@httpError`.

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

## Platform support

JVM only for now. Nothing in the interpreter is JVM-specific, so JS and Native are open — the http4s
and smithy4s dependencies already cross-build.

## License

Apache 2.0.
