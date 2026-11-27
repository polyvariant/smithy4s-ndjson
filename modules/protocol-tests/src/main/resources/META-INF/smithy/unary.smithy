$version: "2"

// Operations that don't stream at all. The protocol promises they behave
// exactly as under `alloy#simpleRestJson`, so these cases are a spot check of
// that promise — the metadata bindings, a structure payload and a declared
// error — rather than a second copy of alloy's own suite.
namespace org.polyvariant.ndjson.compliance

use org.polyvariant.ndjson#ndjsonRestJson
use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

/// Every metadata binding at once: label, query, header and prefix headers in,
/// header, prefix headers and response code out, with a JSON body alongside.
@http(method: "POST", uri: "/unary/{label}", code: 200)
operation UnaryBindings {
    input := {
        @httpLabel
        @required
        label: String

        @httpQuery("q")
        query: String

        @httpQuery("list")
        queryList: StringList

        @httpHeader("X-Header")
        header: String

        /// A timestamp in a header is an HTTP date by default.
        @httpHeader("X-Stamp")
        stamp: Timestamp

        @httpPrefixHeaders("x-meta-")
        meta: StringMap

        @jsonName("renamed")
        body: String

        @timestampFormat("date-time")
        at: Timestamp
    }

    output := {
        @httpHeader("X-Header")
        header: String

        @httpPrefixHeaders("x-meta-")
        meta: StringMap

        @httpResponseCode
        code: Integer

        @jsonName("renamed")
        body: String
    }

    errors: [
        NotFound
    ]
}

apply UnaryBindings @httpRequestTests([
    {
        id: "NdjsonRestJsonUnaryBindingsRequest"
        documentation: "Label, query, header and prefix-header bindings are read alongside a JSON body."
        protocol: ndjsonRestJson
        method: "POST"
        uri: "/unary/hello%20world"
        queryParams: ["q=search", "list=a", "list=b"]
        headers: { "Content-Type": "application/json", "X-Header": "h", "X-Stamp": "Tue, 29 Apr 2014 18:30:38 GMT", "x-meta-one": "1" }
        body: """
            {"renamed":"payload","at":"2014-04-29T18:30:38Z"}"""
        bodyMediaType: "application/json"
        params: {
            label: "hello world"
            query: "search"
            queryList: ["a", "b"]
            header: "h"
            stamp: 1398796238
            meta: { one: "1" }
            body: "payload"
            at: 1398796238
        }
    }
])

apply UnaryBindings @httpResponseTests([
    {
        id: "NdjsonRestJsonUnaryBindingsResponse"
        documentation: "Header, prefix-header and response-code bindings are written alongside a JSON body."
        protocol: ndjsonRestJson
        code: 201
        headers: { "Content-Type": "application/json", "X-Header": "h", "x-meta-one": "1" }
        body: """
            {"renamed":"payload"}"""
        bodyMediaType: "application/json"
        params: {
            header: "h"
            meta: { one: "1" }
            code: 201
            body: "payload"
        }
    }
])

@error("client")
@httpError(404)
structure NotFound {
    @required
    message: String

    @httpHeader("X-Reason")
    reason: String
}

apply NotFound @httpResponseTests([
    {
        id: "NdjsonRestJsonNotFoundError"
        documentation: "A declared error gets its @httpError status, its header bindings and the error type header."
        protocol: ndjsonRestJson
        code: 404
        headers: { "Content-Type": "application/json", "X-Error-Type": "NotFound", "X-Reason": "gone" }
        body: """
            {"message":"no such thing"}"""
        bodyMediaType: "application/json"
        params: { message: "no such thing", reason: "gone" }
    }
])

/// `@httpQueryParams` on its own: a server puts every query parameter in the
/// map, so this operation binds no other.
@readonly
@http(method: "GET", uri: "/unary-query", code: 200)
operation UnaryQueryParams {
    input := {
        @httpQueryParams
        filters: StringMap
    }
}

apply UnaryQueryParams @httpRequestTests([
    {
        id: "NdjsonRestJsonUnaryQueryParamsRequest"
        documentation: "Query parameters bound by @httpQueryParams."
        protocol: ndjsonRestJson
        method: "GET"
        uri: "/unary-query"
        queryParams: ["a=1", "b=2"]
        params: {
            filters: { a: "1", b: "2" }
        }
    }
])

/// A structure as the whole body, both ways, under a non-default success code.
@idempotent
@http(method: "PUT", uri: "/unary-payload", code: 201)
operation UnaryPayload {
    input := {
        @httpPayload
        greeting: Greeting
    }

    output := {
        @httpPayload
        greeting: Greeting
    }
}

structure Greeting {
    @required
    message: String
}

apply UnaryPayload @httpRequestTests([
    {
        id: "NdjsonRestJsonUnaryPayloadRequest"
        documentation: "An @httpPayload structure is the whole request body."
        protocol: ndjsonRestJson
        method: "PUT"
        uri: "/unary-payload"
        headers: { "Content-Type": "application/json" }
        body: """
            {"message":"hi"}"""
        bodyMediaType: "application/json"
        params: {
            greeting: { message: "hi" }
        }
    }
])

apply UnaryPayload @httpResponseTests([
    {
        id: "NdjsonRestJsonUnaryPayloadResponse"
        documentation: "An @httpPayload structure is the whole response body, under the operation's @http code."
        protocol: ndjsonRestJson
        code: 201
        headers: { "Content-Type": "application/json" }
        body: """
            {"message":"hi"}"""
        bodyMediaType: "application/json"
        params: {
            greeting: { message: "hi" }
        }
    }
])
