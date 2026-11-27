$version: "2"

// `@streaming union` marked `@serverSentEvents`: one event per value, as
// `text/event-stream`, its `data` the value's JSON; then, if the trait names
// a `terminator`, one more event with that as its `data`.
//
// Reading follows the event-stream format: lines end in `\n`, `\r\n` or `\r`;
// `data` fields are joined with `\n` until a blank line ends the event;
// comments, other fields and events without data are skipped, as is an event
// cut off before its blank line, and the terminator's event. Data that is not
// a value of the union fails the stream.
//
// `Watch` and `Collect` stream a union with a terminator, out and in, and
// carry the cases for reading; `Listen` and `Emit` stream one without.
namespace org.polyvariant.ndjson.compliance

use org.polyvariant.ndjson#ndjsonRestJson
use org.polyvariant.ndjson#serverSentEvents
use smithy.test#eventStreamTests

@streaming
@serverSentEvents(terminator: "[DONE]")
union Notification {
    progress: Progress
    completed: Completed
}

@streaming
@serverSentEvents
union Signal {
    ping: Ping
}

structure Ping {
    @required
    seq: Integer
}

@http(method: "POST", uri: "/watch", code: 200)
operation Watch {
    input := {
        @required
        topic: String
    }

    output := {
        @httpPayload
        notifications: Notification
    }
}

@http(method: "POST", uri: "/collect", code: 200)
operation Collect {
    input := {
        @httpPayload
        notifications: Notification
    }

    output := {
        @required
        received: Integer
    }
}

@http(method: "POST", uri: "/listen", code: 200)
operation Listen {
    input := {
        @required
        topic: String
    }

    output := {
        @httpPayload
        signals: Signal
    }
}

@http(method: "POST", uri: "/emit", code: 200)
operation Emit {
    input := {
        @httpPayload
        signals: Signal
    }

    output := {
        @required
        received: Integer
    }
}

apply Watch @eventStreamTests([
    {
        id: "NdjsonRestJsonWatchSse"
        documentation: "Each value is the data of one event, under text/event-stream, and the terminator's event follows the last."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                    completed: { total: 1 }
                }
                body: """
                    {"completed":{"total":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                body: "[DONE]"
            }
        ]
    }
    {
        id: "NdjsonRestJsonWatchEmptyStream"
        documentation: "A stream with no values is the terminator's event alone."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "quiet" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"quiet"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                body: "[DONE]"
            }
        ]
    }
    {
        id: "NdjsonRestJsonWatchSkipsCommentsAndEventsWithoutData"
        documentation: "A comment and an event without data deliver nothing: :keep-alive\\n\\nid: 1\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "OmtlZXAtYWxpdmUKCg=="
            }
            {
                type: "response"
                bytes: "aWQ6IDEKCg=="
            }
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
                body: "[DONE]"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchIgnoresOtherFields"
        documentation: "The event, id and retry fields are skipped: event: progress\\nid: 7\\nretry: 1000\\ndata: {\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZXZlbnQ6IHByb2dyZXNzCmlkOiA3CnJldHJ5OiAxMDAwCmRhdGE6IHsicHJvZ3Jlc3MiOnsic29GYXIiOjF9fQoK"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchJoinsDataLines"
        documentation: "An event's data fields are joined with newlines: data: {\"progress\":\\ndata: {\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6CmRhdGE6IHsic29GYXIiOjF9fQoK"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchAcceptsCrlf"
        documentation: "Lines may end in \\r\\n: data: {\"progress\":{\"soFar\":1}}\\r\\n\\r\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19DQoNCg=="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchAcceptsCr"
        documentation: "Lines may end in \\r: data: {\"progress\":{\"soFar\":1}}\\r\\r"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19DQ0="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchAcceptsDataWithoutSpace"
        documentation: "The space after a field's colon is optional: data:{\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZGF0YTp7InByb2dyZXNzIjp7InNvRmFyIjoxfX0KCg=="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchIgnoresByteOrderMark"
        documentation: "A byte order mark at the start of the stream is skipped: \\uFEFFdata: {\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "77u/ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19Cgo="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchDropsUnterminatedEvent"
        documentation: "An event cut off before its blank line is not delivered: data: {\"progress\":{\"soFar\":2}}\\n at the end of the stream."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
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
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6Mn19Cg=="
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonWatchRejectsMalformedData"
        documentation: "Data that is not JSON fails the stream: data: not json\\n\\n"
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "news" }
        initialRequest: {
            method: "POST"
            uri: "/watch"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"news"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "ZGF0YTogbm90IGpzb24KCg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
])

apply Collect @eventStreamTests([
    {
        id: "NdjsonRestJsonCollectSse"
        documentation: "Each value is the data of one event, under text/event-stream, and the terminator's event follows the last."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 2 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":2}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                params: {
                    completed: { total: 1 }
                }
                body: """
                    {"completed":{"total":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                body: "[DONE]"
            }
        ]
    }
    {
        id: "NdjsonRestJsonCollectEmptyStream"
        documentation: "A stream with no values is the terminator's event alone."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 0 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":0}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                body: "[DONE]"
            }
        ]
    }
    {
        id: "NdjsonRestJsonCollectSkipsCommentsAndEventsWithoutData"
        documentation: "A comment and an event without data deliver nothing: :keep-alive\\n\\nid: 1\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "OmtlZXAtYWxpdmUKCg=="
            }
            {
                type: "request"
                bytes: "aWQ6IDEKCg=="
            }
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                body: "[DONE]"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectIgnoresOtherFields"
        documentation: "The event, id and retry fields are skipped: event: progress\\nid: 7\\nretry: 1000\\ndata: {\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "ZXZlbnQ6IHByb2dyZXNzCmlkOiA3CnJldHJ5OiAxMDAwCmRhdGE6IHsicHJvZ3Jlc3MiOnsic29GYXIiOjF9fQoK"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectJoinsDataLines"
        documentation: "An event's data fields are joined with newlines: data: {\"progress\":\\ndata: {\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6CmRhdGE6IHsic29GYXIiOjF9fQoK"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectAcceptsCrlf"
        documentation: "Lines may end in \\r\\n: data: {\"progress\":{\"soFar\":1}}\\r\\n\\r\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19DQoNCg=="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectAcceptsCr"
        documentation: "Lines may end in \\r: data: {\"progress\":{\"soFar\":1}}\\r\\r"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19DQ0="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectAcceptsDataWithoutSpace"
        documentation: "The space after a field's colon is optional: data:{\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "ZGF0YTp7InByb2dyZXNzIjp7InNvRmFyIjoxfX0KCg=="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectIgnoresByteOrderMark"
        documentation: "A byte order mark at the start of the stream is skipped: \\uFEFFdata: {\"progress\":{\"soFar\":1}}\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
                bytes: "77u/ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6MX19Cgo="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectDropsUnterminatedEvent"
        documentation: "An event cut off before its blank line is not delivered: data: {\"progress\":{\"soFar\":2}}\\n at the end of the stream."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 1 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":1}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    progress: { soFar: 1 }
                }
                body: """
                    {"progress":{"soFar":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                bytes: "ZGF0YTogeyJwcm9ncmVzcyI6eyJzb0ZhciI6Mn19Cg=="
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonCollectRejectsMalformedData"
        documentation: "Data that is not JSON is a 400: data: not json\\n\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/collect"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "ZGF0YTogbm90IGpzb24KCg=="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
])

apply Listen @eventStreamTests([
    {
        id: "NdjsonRestJsonListenSseWithoutTerminator"
        documentation: "Without a terminator, the stream ends with the last value's event."
        protocol: ndjsonRestJson
        initialRequestParams: { topic: "pings" }
        initialRequest: {
            method: "POST"
            uri: "/listen"
            headers: { "Content-Type": "application/json" }
            body: """
                {"topic":"pings"}"""
            bodyMediaType: "application/json"
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "text/event-stream" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    ping: { seq: 1 }
                }
                body: """
                    {"ping":{"seq":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    ping: { seq: 2 }
                }
                body: """
                    {"ping":{"seq":2}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
])

apply Emit @eventStreamTests([
    {
        id: "NdjsonRestJsonEmitSseWithoutTerminator"
        documentation: "Without a terminator, the stream ends with the last value's event."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/emit"
            headers: { "Content-Type": "text/event-stream" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponseParams: { received: 2 }
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/json" }
            body: """
                {"received":2}"""
            bodyMediaType: "application/json"
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    ping: { seq: 1 }
                }
                body: """
                    {"ping":{"seq":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                params: {
                    ping: { seq: 2 }
                }
                body: """
                    {"ping":{"seq":2}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
])
