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

import org.http4s.MediaType
import smithy4s.schema.Primitive
import smithy4s.schema.Schema

/** How a `@streaming` member is carried on the wire.
  *
  * Smithy restricts `@streaming` to `:is(blob, union)`, and this protocol reads that distinction as
  * the choice of framing — identically in both directions, so an operation streams the same way in
  * as it does out:
  *
  *   - a `@streaming blob` is the body verbatim, `application/octet-stream`;
  *   - a `@streaming union` is one JSON value per line, `application/x-ndjson`.
  *
  * Nothing else can reach here: a `@streaming` list or string is rejected by Smithy itself, before
  * codegen runs.
  */
private sealed trait StreamFraming[A] extends Product with Serializable {

  def mediaType: MediaType =
    this match {
      case StreamFraming.Raw(_, _) => MediaType.application.`octet-stream`
      case StreamFraming.Ndjson()  => Ndjson.mediaType
    }

}

private object StreamFraming {

  /** A `@streaming blob`: raw bytes, with the newtype codegen gave its element type.
    *
    * Both directions are needed — `wrap` to hand a request body to the impl, `unwrap` to write an
    * impl's stream back out as a response body.
    */
  final case class Raw[A](wrap: Byte => A, unwrap: A => Byte) extends StreamFraming[A]

  /** A `@streaming union`: newline-delimited JSON, encoded by the ordinary payload codecs. */
  final case class Ndjson[A]() extends StreamFraming[A]

  /** Reads the framing off a streamed member's element schema.
    *
    * A `@streaming blob` codegens to a `Newtype[Byte]` (`bijection(byte, ...)`), so a bijection over
    * a byte primitive is how a blob presents itself here; anything else is a union, which is the
    * only other shape `@streaming` admits.
    *
    * Matching the primitive's own tag is what makes this typecheck without a cast: `Primitive` is a
    * GADT, so matching `PByte` refines the bijection's source type to `Byte` within the branch.
    */
  def fromSchema[A](schema: Schema[A]): StreamFraming[A] =
    schema match {
      case Schema.BijectionSchema(Schema.PrimitiveSchema(_, _, Primitive.PByte), bijection) =>
        Raw(bijection.apply, bijection.from)
      case _ =>
        Ndjson()
    }

}
