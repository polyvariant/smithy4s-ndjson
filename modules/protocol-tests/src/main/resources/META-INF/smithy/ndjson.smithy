$version: "2"

// `@streaming union`: one JSON value per line, as `application/x-ndjson`.
//
// Every line is newline-terminated. Reading skips blank lines and accepts
// `\r\n` endings and a last line without its newline; a line that is not a
// value of the union fails the stream.
namespace org.polyvariant.ndjson.compliance

use org.polyvariant.ndjson#ndjsonRestJson
use smithy.test#eventStreamTests
use smithy.test#httpResponseTests

/// NDJSON out, opened by a unary JSON request, with metadata bindings on the
/// response alongside the stream.
@http(method: "POST", uri: "/subscribe/{topic}", code: 200)
operation Subscribe {
    input := {
        @httpLabel
        @required
        topic: String

        @required
        limit: Integer
    }

    output := {
        @httpHeader("X-Subscription")
        subscription: String

        @httpResponseCode
        code: Integer

        @httpPayload
        updates: Update
    }

    errors: [
        TopicNotFound
    ]
}

@streaming
union Update {
    progress: Progress
    completed: Completed
}

structure Progress {
    @required
    @range(min: 0)
    soFar: Long
}

structure Completed {
    @required
    total: Long
}

/// Raised before the stream starts, so it is an ordinary error response.
@error("client")
@httpError(404)
structure TopicNotFound {
    @required
    message: String
}

apply TopicNotFound @httpResponseTests([
    {
        id: "TopicNotFoundError"
        documentation: "A declared error raised before a stream starts is an ordinary error response."
        protocol: ndjsonRestJson
        code: 404
        headers: { "Content-Type": "application/json", "X-Error-Type": "TopicNotFound" }
        body: """
            {"message":"no such topic"}"""
        bodyMediaType: "application/json"
        params: { message: "no such topic" }
    }
])

apply Subscribe @eventStreamTests([
    {
        id: "SubscribeNdjson"
        documentation: "Each value is one line of JSON, newline-terminated, under application/x-ndjson, with the envelope's bindings on the response."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 2 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":2}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { subscription: "sub-1", code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson", "X-Subscription": "sub-1" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    progress: { soFar: 2 }
                }
                body: """
                    {"progress":{"soFar":2}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    completed: { total: 2 }
                }
                body: """
                    {"completed":{"total":2}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "SubscribeEmptyStream"
        documentation: "A stream with no values is an empty body."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "quiet", limit: 0 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/quiet"
            body: """
                {"limit":0}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
            body: ""
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
    }
    {
        id: "SubscribeResponseCode"
        documentation: "@httpResponseCode on the envelope sets the status of a streamed response."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 206 }
        initialResponse: {
            code: 206
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "SubscribeSkipsBlankLines"
        documentation: "A blank line between two values (here: {\"progress\":{\"soFar\":1}}\\n\\n{\"progress\":{\"soFar\":2}}\\n) is skipped."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 2 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":2}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                bytes: "Cg=="
            }
            {
                type: "response"
                params: {
                    progress: { soFar: 2 }
                }
                body: """
                    {"progress":{"soFar":2}}"""
                bodyMediaType: "application/json"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "SubscribeAcceptsCrlf"
        documentation: "A line may end in \\r\\n: {\"progress\":{\"soFar\":1}}\\r\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "eyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19DQo="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "SubscribeAcceptsUnterminatedLastLine"
        documentation: "The last line may lack its newline: {\"progress\":{\"soFar\":1}}"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "eyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "SubscribeRejectsMalformedLine"
        documentation: "A line that is not JSON fails the stream: not json\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "bm90IGpzb24K"
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
    {
        id: "SubscribeRejectsUnknownMember"
        documentation: "A line naming a member the union doesn't have fails the stream: {\"mystery\":{}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "eyJteXN0ZXJ5Ijp7fX0K"
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
    {
        id: "SubscribeRejectsMissingRequired"
        documentation: "A value missing a @required member fails the stream: {\"progress\":{}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "eyJwcm9ncmVzcyI6e319Cg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
    {
        id: "SubscribeRejectsOutOfRange"
        documentation: "A value outside its @range fails the stream: {\"progress\":{\"soFar\":-1}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news", limit: 1 }
        initialRequest: {
            method: "POST"
            uri: "/subscribe/news"
            headers: { "Content-Type": "application/json" }
            body: """
                {"limit":1}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { code: 200 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "eyJwcm9ncmVzcyI6eyJzb0ZhciI6LTF9fQo="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
])

/// NDJSON in, answered with a unary JSON response, with metadata bindings on
/// the request alongside the stream.
@http(method: "POST", uri: "/publish/{channel}", code: 200)
operation Publish {
    input := {
        @httpLabel
        @required
        channel: String

        @httpHeader("X-Batch")
        batch: String

        @httpPayload
        commands: Command
    }

    output := {
        @required
        accepted: Integer
    }
}

@streaming
union Command {
    add: Add
    remove: Remove
}

structure Add {
    @required
    @length(min: 1, max: 8)
    @pattern("^[a-z]+$")
    key: String

    @range(min: 1, max: 10)
    weight: Integer
}

structure Remove {
    @required
    key: String
}

apply Publish @eventStreamTests([
    {
        id: "PublishNdjson"
        documentation: "Each value is one line of JSON, newline-terminated, under application/x-ndjson, with the envelope's bindings on the request."
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory", batch: "b1" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson", "X-Batch": "b1" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { accepted: 2 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"accepted":2}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    add: { key: "apple", weight: 3 }
                }
                body: """
                    {"add":{"key":"apple","weight":3}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                params: {
                    remove: { key: "pear" }
                }
                body: """
                    {"remove":{"key":"pear"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "PublishEmptyStream"
        documentation: "A stream with no values is an empty body."
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
            body: ""
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { accepted: 0 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"accepted":0}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
    }
    {
        id: "PublishSkipsBlankLines"
        documentation: "A blank line between two values (here: {\"remove\":{\"key\":\"a\"}}\\n\\n{\"remove\":{\"key\":\"b\"}}\\n) is skipped."
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { accepted: 2 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"accepted":2}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    remove: { key: "a" }
                }
                body: """
                    {"remove":{"key":"a"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                bytes: "Cg=="
            }
            {
                type: "request"
                params: {
                    remove: { key: "b" }
                }
                body: """
                    {"remove":{"key":"b"}}"""
                bodyMediaType: "application/json"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "PublishAcceptsCrlf"
        documentation: "A line may end in \\r\\n: {\"remove\":{\"key\":\"a\"}}\\r\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { accepted: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"accepted":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    remove: { key: "a" }
                }
                body: """
                    {"remove":{"key":"a"}}"""
                bodyMediaType: "application/json"
                bytes: "eyJyZW1vdmUiOnsia2V5IjoiYSJ9fQ0K"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "PublishAcceptsUnterminatedLastLine"
        documentation: "The last line may lack its newline: {\"remove\":{\"key\":\"a\"}}"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { accepted: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"accepted":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    remove: { key: "a" }
                }
                body: """
                    {"remove":{"key":"a"}}"""
                bodyMediaType: "application/json"
                bytes: "eyJyZW1vdmUiOnsia2V5IjoiYSJ9fQ=="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "PublishRejectsMalformedLine"
        documentation: "A line that is not JSON is a 400: not json\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "bm90IGpzb24K"
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "PublishRejectsUnknownMember"
        documentation: "A line naming a member the union doesn't have is a 400: {\"mystery\":{}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJteXN0ZXJ5Ijp7fX0K"
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "PublishRejectsMissingRequired"
        documentation: "A value missing a @required member is a 400: {\"add\":{}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJhZGQiOnt9fQo="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "PublishRejectsTooLong"
        documentation: "A value over its @length is a 400: {\"add\":{\"key\":\"abcdefghi\"}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJhZGQiOnsia2V5IjoiYWJjZGVmZ2hpIn19Cg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "PublishRejectsPatternMismatch"
        documentation: "A value not matching its @pattern is a 400: {\"add\":{\"key\":\"ABC\"}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJhZGQiOnsia2V5IjoiQUJDIn19Cg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "PublishRejectsOutOfRange"
        documentation: "A value outside its @range is a 400: {\"add\":{\"key\":\"abc\",\"weight\":11}}\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { channel: "inventory" }
        initialRequest: {
            method: "POST"
            uri: "/publish/inventory"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJhZGQiOnsia2V5IjoiYWJjIiwid2VpZ2h0IjoxMX19Cg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
])
