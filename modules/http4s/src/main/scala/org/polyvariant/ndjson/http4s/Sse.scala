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

import java.nio.charset.StandardCharsets.UTF_8

/** The server-sent-events framing: `text/event-stream`, one event per value.
  *
  * Framing only — as with [[Ndjson]], the values are encoded by smithy4s's ordinary JSON payload
  * codecs, so an event's data is byte-for-byte what `simpleRestJson` would have written as a whole
  * body.
  *
  * @see
  *   https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream
  */
object Sse {

  val mediaType: MediaType = new MediaType("text", "event-stream")

  /** Encodes one event: a `data` field per line of `data`, then the blank line that dispatches it.
    *
    * The payload codecs write a value on one line, so in practice that is a single field; splitting
    * at every line break a reader recognises (`\r\n`, `\r` or `\n`) keeps the event well-formed for
    * any data, since such a break would otherwise end the field.
    */
  def encodeEvent(data: String): Chunk[Byte] = {
    val fields = data.split("\r\n|\r|\n", -1).map(line => s"data: $line\n").mkString
    Chunk.array(s"$fields\n".getBytes(UTF_8))
  }

  /** Frames a stream of values as server-sent events, followed by the terminator's event, if any.
    *
    * The terminator is only written once the values complete, so a stream that fails ends without
    * it, which is how a client that expects it can tell the stream is incomplete.
    */
  def encode[F[_], A](
    values: Stream[F, A],
    codec: Encoder[Blob, A],
    terminator: Option[String],
  ): Stream[F, Byte] =
    values.mapChunks(_.flatMap(value => encodeEvent(codec.encode(value).toUTF8String))) ++
      Stream.emits(terminator.toList).flatMap(data => Stream.chunk(encodeEvent(data)))

  /** Reads a server-sent-event byte stream back into its values.
    *
    * Follows the format's parsing rules for what matters here: lines end in `\r\n`, `\r` or `\n`,
    * `data` fields are joined by newlines until a blank line dispatches the event, comments (lines
    * starting with `:`) and the other fields (`event`, `id`, `retry`) are ignored, an event without
    * data is no event, and one cut off before its blank line is not dispatched. An event whose data
    * is the terminator is skipped; data that is not valid JSON for the element schema fails the
    * stream, as a malformed NDJSON line does.
    */
  def decode[F[_]: MonadThrow, A](
    bytes: Stream[F, Byte],
    codec: PayloadDecoder[A],
    terminator: Option[String],
  ): Stream[F, A] =
    bytes
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .mapAccumulate(Vector.empty[String]) { (data, line) =>
        if (line.isEmpty)
          (Vector.empty, Some(data))
        else
          (dataField(line).fold(data)(data :+ _), None)
      }
      .collect { case (_, Some(data)) if data.nonEmpty => data.mkString("\n") }
      .filterNot(terminator.contains)
      .evalMap(data => codec.decode(Blob(data)).liftTo[F])

  /** The value of a `data` field, or `None` for a comment or any other field.
    *
    * A field's value starts after its colon, minus a single leading space; a line without a colon
    * is a field with an empty value.
    */
  private def dataField(line: String): Option[String] = {
    val (name, rest) = line.span(_ != ':')
    val value = rest.drop(1)

    Option.when(name == "data")(value.stripPrefix(" "))
  }

}
