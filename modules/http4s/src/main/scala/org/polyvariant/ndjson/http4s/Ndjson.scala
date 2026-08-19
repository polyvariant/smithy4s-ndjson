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

import cats.MonadThrow
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream
import org.http4s.MediaType
import smithy4s.Blob
import smithy4s.codecs.Encoder
import smithy4s.codecs.PayloadDecoder

/** The newline-delimited-JSON framing: `application/x-ndjson`, one JSON value per line.
  *
  * Framing only — the values themselves are encoded by smithy4s's ordinary JSON payload codecs, so
  * a line is byte-for-byte what `simpleRestJson` would have written as a whole body.
  */
object Ndjson {

  val mediaType: MediaType = new MediaType("application", "x-ndjson")

  private val newline: Chunk[Byte] = Chunk.singleton('\n'.toByte)

  /** Encodes one value as a single NDJSON line, terminator included.
    *
    * Every line is terminated rather than separated, so a truncated stream is detectable: a reader
    * that reaches EOF mid-line knows it did not get everything. That matters here because a
    * streaming response cannot report a late failure via its status code.
    */
  def encodeLine[A](value: A, codec: Encoder[Blob, A]): Chunk[Byte] =
    Chunk.array(codec.encode(value).toArray) ++ newline

  /** Frames a stream of values as an NDJSON byte stream. */
  def encode[F[_], A](values: Stream[F, A], codec: Encoder[Blob, A]): Stream[F, Byte] =
    values.mapChunks(_.flatMap(encodeLine(_, codec)))

  /** Reads an NDJSON byte stream back into its values.
    *
    * Blank lines are skipped rather than decoded, so the trailing newline that [[encodeLine]]
    * writes after the final value does not read back as an extra element. A line that is present
    * but not valid JSON for the element schema fails the stream: it is a malformed request, and
    * continuing past it would hand the impl a silently short stream.
    */
  def decode[F[_]: MonadThrow, A](
    bytes: Stream[F, Byte],
    codec: PayloadDecoder[A],
  ): Stream[F, A] =
    bytes
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .filter(_.trim.nonEmpty)
      .evalMap(line => codec.decode(Blob(line)).liftTo[F])

}
