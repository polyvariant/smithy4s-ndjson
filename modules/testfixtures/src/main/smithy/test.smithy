$version: "2"

namespace org.polyvariant.ndjson.test

use org.polyvariant.ndjson#ndjsonRestJson

/// Exercises every shape the protocol admits, in one service: binary in, NDJSON
/// out, plain JSON in and out, and metadata bindings alongside a streamed body.
///
/// Lives in its own module rather than `ndjson`'s test scope because the
/// smithy4s sbt plugin only wires codegen into `Compile`; keeping it out of
/// `ndjson` proper also keeps the fixtures out of that module's published jar.
@ndjsonRestJson
service TestService {
    operations: [
        Echo
        Upload
        Greet
        Tagged
        Fallible
    ]
}

/// Plain JSON in, NDJSON out: a unary request that opens a stream of events.
@http(method: "POST", uri: "/echo", code: 200)
operation Echo {
    input := {
        @required
        count: Integer
    }

    output := {
        @httpPayload
        @required
        event: Event
    }
}

/// Binary in, NDJSON out: a raw upload answered by a stream of events.
@http(method: "POST", uri: "/upload", code: 200)
operation Upload {
    input := {
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

/// No streaming at all — must behave exactly as simpleRestJson would.
@readonly
@http(method: "GET", uri: "/greet/{name}", code: 200)
operation Greet {
    input := {
        @httpLabel
        @required
        name: String

        @httpQuery("loud")
        loud: Boolean
    }

    output := {
        @required
        message: String
    }
}

/// Metadata bindings alongside a streamed request body: the label must be
/// decoded from the path even though the body is claimed by the stream.
@http(method: "POST", uri: "/tagged/{tag}", code: 202)
operation Tagged {
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

/// A unary operation with declared errors, so the interpreter's typed-error encoding is exercised
/// against real codegen output — including one error whose @httpError differs from the default.
@readonly
@http(method: "GET", uri: "/fallible/{which}", code: 200)
operation Fallible {
    input := {
        @httpLabel
        @required
        which: String
    }

    output := {
        @required
        ok: String
    }

    errors: [
        NotThere
        Unprocessable
    ]
}

@error("client")
@httpError(404)
structure NotThere {
    @required
    message: String
}

@error("client")
@httpError(422)
structure Unprocessable {
    @required
    message: String
}

@streaming
blob Payload

@streaming
union Event {
    progress: Progress
    completed: Completed
    failed: Failed
}

structure Progress {
    @required
    soFar: Long
}

structure Completed {
    @required
    total: Long
}

structure Failed {
    @required
    message: String
}
