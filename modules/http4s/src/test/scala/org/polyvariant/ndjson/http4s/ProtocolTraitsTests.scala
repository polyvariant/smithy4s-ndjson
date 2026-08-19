/*
 * Copyright 2026 Polyvariant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polyvariant.ndjson.http4s

import org.polyvariant.ndjson.NdjsonRestJson
import smithy4s.ShapeId
import weaver.FunSuite

/** Pins the protocol's `@protocolDefinition` trait list against `alloy#simpleRestJson`'s.
  *
  * The list isn't documentation: `hintMask` is derived from it, so a trait missing from it is
  * silently dropped when a body is encoded or decoded — no error, just the wrong bytes. A missing
  * `@timestampFormat` writes an epoch number where the peer expects a date-time string, and the
  * first sign of it is a decode failure on the other side of the wire.
  *
  * So the invariant is asserted against `SimpleRestJson`'s own list rather than a copy of it: this
  * fails the build when alloy adds a trait we haven't picked up, which is exactly when the two
  * protocols would start disagreeing about the same non-streaming body.
  */
object ProtocolTraitsTests extends FunSuite {

  private val ours = NdjsonRestJson.protocol.traits
  private val theirs = alloy.SimpleRestJson.protocol.traits

  private val streaming = ShapeId("smithy.api", "streaming")

  test("every trait simpleRestJson interprets is interpreted here too") {
    expect.same(theirs.diff(ours), Set.empty[ShapeId])
  }

  test("@streaming is the only trait this protocol adds") {
    expect.same(ours.diff(theirs), Set(streaming))
  }

}
