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

package org.polyvariant.ndjson.compliance

import alloy.Nullable
import smithy4s.Blob
import smithy4s.Document
import smithy4s.Hints
import smithy4s.ShapeId
import smithy4s.codecs.PayloadError
import smithy4s.internals.DocumentDecoder
import smithy4s.internals.DocumentDecoderSchemaVisitor
import smithy4s.schema.CachedSchemaCompiler
import smithy4s.schema.CompilationCache
import smithy4s.schema.Primitive
import smithy4s.schema.Schema
import smithy4s.time.OffsetDateTime
import smithy4s.time.Timestamp
import smithy4s.time.ZoneOffset

/** Reads a case's `params` (as `CaseModel.paramsDocument` hands them over) into the value they
  * describe.
  *
  * This is smithy4s's `CanonicalSmithyDecoder` — `params` are Smithy's node form, which no protocol
  * trait shapes — with one difference: it keeps `@nullable`. That trait is the only way the node
  * form tells an explicit `null` from an absent member, and the canonical decoder drops it along
  * with every other hint, reading `null` as absent.
  */
object ParamsDecoder {

  def decode[A](schema: Schema[A], params: Document): Either[PayloadError, A] =
    compiler.fromSchema(schema.transformHintsTransitively(_.filter(_.keyId == Nullable.id))).decode(params)

  private object compiler extends CachedSchemaCompiler.Impl[Document.Decoder] {

    protected type Aux[A] = DocumentDecoder[A]

    def fromSchema[A](schema: Schema[A], cache: Cache): Document.Decoder[A] = {
      val compiled = schema.compile(new Visitor(cache))
      new Document.Decoder[A] {
        def decode(document: Document): Either[PayloadError, A] =
          try Right(compiled(Nil, document))
          catch { case e: PayloadError => Left(e) }
      }
    }

  }

  /** As the canonical decoder: a timestamp is epoch seconds, a blob is its text. */
  private final class Visitor(cache: CompilationCache[DocumentDecoder])
    extends DocumentDecoderSchemaVisitor(cache) {

    override def primitive[P](shapeId: ShapeId, hints: Hints, tag: Primitive[P])
      : DocumentDecoder[P] =
      tag match {
        case Primitive.PTimestamp =>
          from("Timestamp") { case Document.DNumber(seconds) => timestamp(seconds) }
        case Primitive.PBlob => from("Blob") { case Document.DString(text) => Blob(text) }
        case Primitive.POffsetDateTime =>
          from("OffsetDateTime") { case Document.DNumber(seconds) =>
            val t = timestamp(seconds)
            OffsetDateTime(t.epochSecond, t.nano, ZoneOffset.Zero)
          }
        case _ => super.primitive(shapeId, hints, tag)
      }

    private def timestamp(seconds: BigDecimal): Timestamp = {
      val whole = seconds.toLong
      Timestamp(whole, ((seconds - whole) * 1000000000).toInt)
    }

  }

}
