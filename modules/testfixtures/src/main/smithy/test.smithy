$version: "2"

namespace org.polyvariant.ndjson.test

use alloy#dateFormat
use alloy#uuidFormat
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
        Ingest
        Download
        Relay
        Formats
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

/// No streaming at all — must behave exactly as simpleRestJson would, including
/// the metadata bindings on the way out: a header and an overridden status.
@readonly
@http(method: "GET", uri: "/greet/{name}", code: 200)
operation Greet {
    input := {
        @httpLabel
        @required
        name: String

        @httpQuery("loud")
        loud: Boolean

        @httpHeader("X-Caller")
        caller: String
    }

    output := {
        @required
        message: String

        @httpHeader("X-Greeting-Kind")
        kind: String

        @httpResponseCode
        status: Integer
    }
}

/// Metadata bindings alongside a streamed request body: the label must be
/// decoded from the path even though the body is claimed by the stream, and the
/// envelope's own header binding must survive onto the streamed response.
@http(method: "POST", uri: "/tagged/{tag}", code: 202)
operation Tagged {
    input := {
        @httpLabel
        @required
        tag: String

        @httpHeader("X-Tag-Source")
        source: String

        @httpPayload
        @required
        body: Payload
    }

    output := {
        @httpHeader("X-Tag-Echo")
        echo: String

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

    @httpHeader("X-Missing-What")
    what: String
}

@error("client")
@httpError(422)
structure Unprocessable {
    @required
    message: String
}

/// NDJSON in: a `@streaming` union on the *input* side, decoded one value per line.
@http(method: "POST", uri: "/ingest", code: 200)
operation Ingest {
    input := {
        @httpPayload
        @required
        commands: Command
    }

    output := {
        @required
        applied: Integer
    }
}

/// Binary out: a `@streaming blob` on the *output* side, written verbatim.
@readonly
@http(method: "GET", uri: "/download/{name}", code: 200)
operation Download {
    input := {
        @httpLabel
        @required
        name: String
    }

    output := {
        @httpHeader("X-Download-Name")
        downloadName: String

        @httpPayload
        @required
        content: Payload
    }
}

/// Binary in, binary out: both edges raw, so the framing rule is exercised
/// symmetrically within one operation.
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

@streaming
union Command {
    add: Add
    remove: Remove
}

structure Add {
    @required
    key: String
}

structure Remove {
    @required
    key: String
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

/// The traits whose effect lives purely in the JSON codec, on both paths at once.
///
/// These are the ones a `@protocolDefinition` list can omit silently: the hint mask is built from
/// that list, so a trait absent from it doesn't fail — it just changes the bytes. A `@timestampFormat`
/// left out writes an epoch number where a date-time string belongs, and `@jsonName` reverts to the
/// member name. Both the unary body and the streamed elements go through the same masked codecs, so
/// the operation exercises them together.
@http(method: "POST", uri: "/formats", code: 200)
operation Formats {
    input := {
        /// Without `@timestampFormat` in the protocol's trait list this reads as an epoch second,
        /// so a date-time string fails to decode — which is how the omission originally surfaced.
        @required
        @timestampFormat("date-time")
        stamp: Timestamp

        @required
        @jsonName("renamed_in")
        renamed: String
    }

    output := {
        @httpPayload
        @required
        record: Record
    }
}

/// Streamed, so the mask is exercised on the NDJSON path too — one of these per line.
@streaming
union Record {
    entry: Entry
}

structure Entry {
    /// `@timestampFormat` is the trait whose omission originally surfaced this: without it in the
    /// mask the value is written as an epoch second, not an RFC-3339 string.
    @required
    @timestampFormat("date-time")
    at: Timestamp

    @required
    @jsonName("renamed_out")
    renamed: String

    @required
    id: Uuid

    @required
    day: Day
}

/// `alloy#uuidFormat` and `alloy#dateFormat` are shape-level traits, so the members above carry
/// them via these aliases rather than directly.
@uuidFormat
string Uuid

@dateFormat
string Day
