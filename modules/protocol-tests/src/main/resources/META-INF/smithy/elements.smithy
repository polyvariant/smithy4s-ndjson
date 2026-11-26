$version: "2"

// How a streamed value is encoded: one case per trait in the protocol's
// `@protocolDefinition` list whose effect is on the JSON itself.
//
// The list is the JSON codecs' hint mask, so a trait missing from it isn't an
// error: it silently changes the bytes. A streamed value goes through the same
// codecs as a unary body, so every such trait is checked here, on a line of
// NDJSON, and both ways — each case has the same value as a `request` event
// and a `response` event, so a client and a server each write it once and
// read it once.
namespace org.polyvariant.ndjson.compliance

use alloy#dateFormat
use alloy#discriminated
use alloy#durationSecondsFormat
use alloy#jsonUnknown
use alloy#localTimeFormat
use alloy#nullable
use alloy#offsetDateTimeFormat
use alloy#openEnum
use alloy#preserveKeyOrder
use alloy#untagged
use alloy#uuidFormat
use org.polyvariant.ndjson#ndjsonRestJson
use smithy.test#eventStreamTests

@http(method: "POST", uri: "/records", code: 200)
operation EchoRecords {
    input := {
        @httpPayload
        records: Record
    }

    output := {
        @httpPayload
        records: Record
    }
}

@streaming
union Record {
    @jsonName("renamed_tag")
    renamed: Renamed

    timestamps: Timestamps

    defaults: Defaults

    nullables: Nullables

    discriminated: Discriminated

    untagged: Untagged

    open: Open

    extensible: Extensible

    enums: Enums

    formats: Formats

    ordered: Ordered
}

structure Renamed {
    @required
    @jsonName("display_name")
    name: String
}

structure Timestamps {
    /// Epoch seconds: the default in a JSON body.
    @required
    epoch: Timestamp

    @required
    @timestampFormat("date-time")
    dateTime: Timestamp

    @required
    @timestampFormat("http-date")
    httpDate: Timestamp
}

structure Defaults {
    count: Integer = 1
    label: String = "none"
}

structure Nullables {
    @nullable
    note: String
}

// A member of a streamed union must target a structure, so the cases for a nested union or a map
// wrap it in one.
structure Discriminated {
    @required
    shape: Shape
}

@discriminated("kind")
union Shape {
    circle: Circle
    square: Square
}

structure Circle {
    @required
    radius: Integer
}

structure Square {
    @required
    side: Integer
}

structure Untagged {
    @required
    amount: Amount
}

@untagged
union Amount {
    number: Integer
    text: String
}

structure Open {
    @required
    choice: OpenChoice
}

union OpenChoice {
    known: String

    @jsonUnknown
    other: Document
}

structure Extensible {
    @required
    name: String

    @jsonUnknown
    rest: UnknownMembers
}

map UnknownMembers {
    key: String
    value: Document
}

structure Enums {
    @required
    color: Color

    @required
    level: Level
}

@openEnum
enum Color {
    RED
    GREEN
}

@openEnum
intEnum Level {
    LOW = 1
    HIGH = 2
}

structure Formats {
    @required
    id: Uuid

    @required
    day: Day

    @required
    time: TimeOfDay

    @required
    duration: Seconds

    @required
    at: OffsetDateTime
}

@uuidFormat
string Uuid

@dateFormat
string Day

@localTimeFormat
string TimeOfDay

@durationSecondsFormat
bigDecimal Seconds

@offsetDateTimeFormat
@timestampFormat("date-time")
timestamp OffsetDateTime

// On a document rather than a map: smithy4s 0.19.13 generates code that doesn't compile for a map
// carrying the trait, whether on the map shape or the member.
structure Ordered {
    @required
    @preserveKeyOrder
    entries: Document
}

apply EchoRecords @eventStreamTests([
    {
        id: "NdjsonRestJsonRecordsJsonName"
        documentation: "@jsonName renames a union's tag and a structure's member."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    renamed: { name: "x" }
                }
                body: """
                    {"renamed_tag":{"display_name":"x"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    renamed: { name: "x" }
                }
                body: """
                    {"renamed_tag":{"display_name":"x"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsTimestampFormat"
        documentation: "A timestamp is epoch seconds by default, and @timestampFormat picks another format."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    timestamps: { epoch: 1398796238, dateTime: "2014-04-29T18:30:38Z", httpDate: "Tue, 29 Apr 2014 18:30:38 GMT" }
                }
                body: """
                    {"timestamps":{"epoch":1398796238,"dateTime":"2014-04-29T18:30:38Z","httpDate":"Tue, 29 Apr 2014 18:30:38 GMT"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    timestamps: { epoch: 1398796238, dateTime: "2014-04-29T18:30:38Z", httpDate: "Tue, 29 Apr 2014 18:30:38 GMT" }
                }
                body: """
                    {"timestamps":{"epoch":1398796238,"dateTime":"2014-04-29T18:30:38Z","httpDate":"Tue, 29 Apr 2014 18:30:38 GMT"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsDefaultsOnServer"
        documentation: "A member left out takes its @default when read."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    defaults: { count: 1, label: "none" }
                }
                body: """
                    {"defaults":{}}"""
                bodyMediaType: "application/json"
            }
        ]
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonRecordsDefaultsOnClient"
        documentation: "A member left out takes its @default when read."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                params: {
                    defaults: { count: 1, label: "none" }
                }
                body: """
                    {"defaults":{}}"""
                bodyMediaType: "application/json"
            }
        ]
        appliesTo: "client"
    }
    {
        id: "NdjsonRestJsonRecordsNullable"
        documentation: "An explicit null in a @nullable member is kept, and written back."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    nullables: { note: null }
                }
                body: """
                    {"nullables":{"note":null}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    nullables: { note: null }
                }
                body: """
                    {"nullables":{"note":null}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsDiscriminated"
        documentation: "A @discriminated union puts its tag in the named field, beside the member's own."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    discriminated: {
                        shape: {
                            circle: { radius: 2 }
                        }
                    }
                }
                body: """
                    {"discriminated":{"shape":{"kind":"circle","radius":2}}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    discriminated: {
                        shape: {
                            circle: { radius: 2 }
                        }
                    }
                }
                body: """
                    {"discriminated":{"shape":{"kind":"circle","radius":2}}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsUntagged"
        documentation: "An @untagged union is the member's value alone."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    untagged: {
                        amount: { number: 5 }
                    }
                }
                body: """
                    {"untagged":{"amount":5}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "request"
                params: {
                    untagged: {
                        amount: { text: "five" }
                    }
                }
                body: """
                    {"untagged":{"amount":"five"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    untagged: {
                        amount: { number: 5 }
                    }
                }
                body: """
                    {"untagged":{"amount":5}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    untagged: {
                        amount: { text: "five" }
                    }
                }
                body: """
                    {"untagged":{"amount":"five"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsJsonUnknownUnionMember"
        documentation: "A union's @jsonUnknown member holds a tag the union doesn't declare, and writes it back as it was."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    open: {
                        choice: {
                            other: {
                                future: { x: 1 }
                            }
                        }
                    }
                }
                body: """
                    {"open":{"choice":{"future":{"x":1}}}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    open: {
                        choice: {
                            other: {
                                future: { x: 1 }
                            }
                        }
                    }
                }
                body: """
                    {"open":{"choice":{"future":{"x":1}}}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsJsonUnknownStructureMembers"
        documentation: "A structure's @jsonUnknown map holds the members the structure doesn't declare, and writes them back beside the rest."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    extensible: {
                        name: "n"
                        rest: { color: "red" }
                    }
                }
                body: """
                    {"extensible":{"name":"n","color":"red"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    extensible: {
                        name: "n"
                        rest: { color: "red" }
                    }
                }
                body: """
                    {"extensible":{"name":"n","color":"red"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsOpenEnum"
        documentation: "An @openEnum, string and int. Only declared values: Smithy's node validation doesn't know @openEnum and rejects any other value in params, so reading an undeclared one can't be written as a case."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    enums: { color: "RED", level: 1 }
                }
                body: """
                    {"enums":{"color":"RED","level":1}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    enums: { color: "RED", level: 1 }
                }
                body: """
                    {"enums":{"color":"RED","level":1}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsAlloyFormats"
        documentation: "@uuidFormat, @dateFormat, @localTimeFormat, @durationSecondsFormat and @offsetDateTimeFormat."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    formats: {
                        id: "51216269-c0c8-454a-871e-329513e54e23"
                        day: "2025-08-15"
                        time: "13:26:51.123456789"
                        duration: 86400.000000001
                        at: "2025-08-15T20:26:51Z"
                    }
                }
                body: """
                    {"formats":{"id":"51216269-c0c8-454a-871e-329513e54e23","day":"2025-08-15","time":"13:26:51.123456789","duration":86400.000000001,"at":"2025-08-15T20:26:51Z"}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    formats: {
                        id: "51216269-c0c8-454a-871e-329513e54e23"
                        day: "2025-08-15"
                        time: "13:26:51.123456789"
                        duration: 86400.000000001
                        at: "2025-08-15T20:26:51Z"
                    }
                }
                body: """
                    {"formats":{"id":"51216269-c0c8-454a-871e-329513e54e23","day":"2025-08-15","time":"13:26:51.123456789","duration":86400.000000001,"at":"2025-08-15T20:26:51Z"}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsPreserveKeyOrder"
        documentation: "A @preserveKeyOrder document. Bodies are compared as JSON, where key order doesn't count, so this only checks such a document is read and written at all."
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                params: {
                    ordered: {
                        entries: { b: 1, a: 2 }
                    }
                }
                body: """
                    {"ordered":{"entries":{"b":1,"a":2}}}"""
                bodyMediaType: "application/json"
            }
            {
                type: "response"
                params: {
                    ordered: {
                        entries: { b: 1, a: 2 }
                    }
                }
                body: """
                    {"ordered":{"entries":{"b":1,"a":2}}}"""
                bodyMediaType: "application/json"
            }
        ]
    }
    {
        id: "NdjsonRestJsonRecordsRejectsInvalidUuidOnServer"
        documentation: "A @uuidFormat string that isn't a UUID is a 400: {\"formats\":{\"id\":\"nope\",...}}\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: { code: 400 }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "request"
                bytes: "eyJmb3JtYXRzIjp7ImlkIjoibm9wZSIsImRheSI6IjIwMjUtMDgtMTUiLCJ0aW1lIjoiMTM6MjY6NTEiLCJkdXJhdGlvbiI6MSwiYXQiOiIyMDI1LTA4LTE1VDIwOjI2OjUxWiJ9fQo="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "server"
    }
    {
        id: "NdjsonRestJsonRecordsRejectsInvalidUuidOnClient"
        documentation: "A @uuidFormat string that isn't a UUID fails the stream: {\"formats\":{\"id\":\"nope\",...}}\\n"
        protocol: ndjsonRestJson
        initialRequest: {
            method: "POST"
            uri: "/records"
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialRequestShape: "smithy.test#InitialHttpRequest"
        initialResponse: {
            code: 200
            headers: { "Content-Type": "application/x-ndjson" }
        }
        initialResponseShape: "smithy.test#InitialHttpResponse"
        events: [
            {
                type: "response"
                bytes: "eyJmb3JtYXRzIjp7ImlkIjoibm9wZSIsImRheSI6IjIwMjUtMDgtMTUiLCJ0aW1lIjoiMTM6MjY6NTEiLCJkdXJhdGlvbiI6MSwiYXQiOiIyMDI1LTA4LTE1VDIwOjI2OjUxWiJ9fQo="
            }
        ]
        expectation: {
            failure: {}
        }
        appliesTo: "client"
    }
])
