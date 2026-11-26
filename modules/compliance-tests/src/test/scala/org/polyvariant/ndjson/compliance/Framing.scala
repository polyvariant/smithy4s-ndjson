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

import cats.syntax.all.*
import org.polyvariant.ndjson.ServerSentEvents
import smithy4s.schema.Schema

/** How a `@streaming union` is framed, as the compliance tests describe it: the bytes an event stands
  * for when it states only its `body`, and how a written body splits back into events.
  *
  * Splitting is strict on purpose. A reader forgives a blank line, `\r\n` or a comment, and the cases
  * check that it does; but these split what an implementation ''wrote'', and the protocol writes
  * none of those.
  */
enum Framing {
  case Ndjson, Sse

  def frame(data: String): String =
    this match {
      case Ndjson => s"$data\n"
      case Sse    => data.split("\n", -1).map(line => s"data: $line\n").mkString + "\n"
    }

  /** The `data` of each event in `body`, or what makes it something this framing doesn't write. */
  def split(body: String): Either[String, List[String]] =
    this match {
      case Ndjson =>
        terminated(body, "\n").flatMap(_.traverse { line =>
          Either.cond(line.nonEmpty && !line.contains('\r'), line, s"unexpected line: ${show(line)}")
        })
      case Sse =>
        terminated(body, "\n\n").flatMap(_.traverse { event =>
          event
            .split("\n", -1)
            .toList
            .traverse { line =>
              Either.cond(
                line.startsWith("data: "),
                line.stripPrefix("data: "),
                s"unexpected line in an event: ${show(line)}",
              )
            }
            .map(_.mkString("\n"))
        })
    }

  /** `body` split into the pieces each terminated by `terminator`. */
  private def terminated(body: String, terminator: String): Either[String, List[String]] =
    if (body.isEmpty)
      Right(Nil)
    else if (!body.endsWith(terminator))
      Left(s"the body doesn't end in ${show(terminator)}: ${show(body)}")
    else
      Right(body.stripSuffix(terminator).split(terminator, -1).toList)

  private def show(s: String): String =
    s.flatMap {
      case '\n'  => "\\n"
      case '\r'  => "\\r"
      case other => other.toString
    }

}

object Framing {

  /** The framing of a streamed union, read off its schema exactly as the interpreter does. */
  def of[A](schema: Schema[A]): Framing =
    if (schema.hints.has(ServerSentEvents))
      Sse
    else
      Ndjson

}
