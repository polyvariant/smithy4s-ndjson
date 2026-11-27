$version: "2"

// Configures the `removeTraits` transformer (smithy-transformations) that this module's codegen
// runs over the compliance tests' model.
//
// `@eventStreamTests` goes: smithy4s renders every trait on an operation as a hint, and it can't
// render the `Blob` that an event's `body` and `bytes` are, so codegen fails on the first case. The
// cases aren't lost — the tests read them from the model itself, at test time.
metadata "smithytransformations#removeTraits" = ["[id = 'smithy.test#eventStreamTests']"]

namespace org.polyvariant.ndjson.compliance.codegen
