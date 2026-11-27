$version: "2"

// `@streaming blob`: the body is the bytes verbatim, labelled with the blob's
// `@mediaType` if it has one and `application/octet-stream` otherwise. In a
// case's `params` the blob is written as its text.
namespace org.polyvariant.ndjson.compliance

use org.polyvariant.ndjson#ndjsonRestJson
use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

@streaming
blob Bytes

/// Comma-separated values, labelled as such.
@streaming
@mediaType("text/csv")
blob Csv

/// Bytes in, with metadata bindings alongside the stream.
@http(method: "POST", uri: "/blob/{name}", code: 200)
operation PutBlob {
    input := {
        @httpLabel
        @required
        name: String

        @httpHeader("X-Checksum")
        checksum: String

        @httpPayload
        @required
        content: Bytes
    }
}

apply PutBlob @httpRequestTests([
    {
        id: "NdjsonRestJsonPutBlobRequest"
        documentation: "The request body is the blob, verbatim, as application/octet-stream."
        protocol: ndjsonRestJson
        method: "POST"
        uri: "/blob/notes.txt"
        headers: { "Content-Type": "application/octet-stream", "X-Checksum": "abc" }
        body: """
            hello
            world"""
        params: {
            name: "notes.txt"
            checksum: "abc"
            content: """
                hello
                world"""
        }
    }
    {
        id: "NdjsonRestJsonPutBlobEmptyRequest"
        documentation: "An empty blob is an empty body."
        protocol: ndjsonRestJson
        method: "POST"
        uri: "/blob/empty"
        headers: { "Content-Type": "application/octet-stream" }
        body: ""
        params: { name: "empty", content: "" }
    }
])

/// Bytes out, with a header binding alongside the stream.
@readonly
@http(method: "GET", uri: "/blob/{name}", code: 200)
operation GetBlob {
    input := {
        @httpLabel
        @required
        name: String
    }

    output := {
        @httpHeader("X-Name")
        name: String

        @httpPayload
        @required
        content: Bytes
    }
}

apply GetBlob @httpResponseTests([
    {
        id: "NdjsonRestJsonGetBlobResponse"
        documentation: "The response body is the blob, verbatim, as application/octet-stream."
        protocol: ndjsonRestJson
        code: 200
        headers: { "Content-Type": "application/octet-stream", "X-Name": "notes.txt" }
        body: """
            hello
            world"""
        params: {
            name: "notes.txt"
            content: """
                hello
                world"""
        }
    }
    {
        id: "NdjsonRestJsonGetBlobEmptyResponse"
        documentation: "An empty blob is an empty body, still labelled application/octet-stream."
        protocol: ndjsonRestJson
        code: 200
        headers: { "Content-Type": "application/octet-stream" }
        body: ""
        params: { content: "" }
    }
])

/// A blob with a `@mediaType`, both ways: the media type labels the bytes and
/// never changes how they are framed.
@http(method: "POST", uri: "/csv", code: 200)
operation EchoCsv {
    input := {
        @httpPayload
        @required
        rows: Csv
    }

    output := {
        @httpPayload
        @required
        rows: Csv
    }
}

apply EchoCsv @httpRequestTests([
    {
        id: "NdjsonRestJsonEchoCsvRequest"
        documentation: "A blob with a @mediaType is sent verbatim, as that media type."
        protocol: ndjsonRestJson
        method: "POST"
        uri: "/csv"
        headers: { "Content-Type": "text/csv" }
        body: """
            a,b
            1,2
            """
        params: {
            rows: """
                a,b
                1,2
                """
        }
    }
])

apply EchoCsv @httpResponseTests([
    {
        id: "NdjsonRestJsonEchoCsvResponse"
        documentation: "A blob with a @mediaType is returned verbatim, as that media type."
        protocol: ndjsonRestJson
        code: 200
        headers: { "Content-Type": "text/csv" }
        body: """
            a,b
            1,2
            """
        params: {
            rows: """
                a,b
                1,2
                """
        }
    }
])
