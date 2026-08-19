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

import cats.effect.IO
import fs2.Stream
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.polyvariant.ndjson.test.*
import weaver.SimpleIOSuite

object NdjsonRestJsonBuilderTests extends SimpleIOSuite {

  /** A [[TestService]] impl whose behaviour is fixed per operation, so each test asserts on the
    * protocol's framing rather than on any logic of its own.
    */
  private val impl: TestServiceGen[WithStreamedIO[IO]] =
    new TestServiceGen[WithStreamedIO[IO]] {

      def greet(name: String, loud: Option[Boolean]) =
        _ =>
          IO.pure(
            (
              GreetOutput(
                if (loud.contains(true))
                  s"HELLO $name"
                else
                  s"hello $name"
              ),
              Stream.empty,
            )
          )

      def echo(count: Int) =
        _ =>
          IO.pure(
            (
              EchoOutput(),
              Stream
                .range(0L, count.toLong)
                .map(i => Event.ProgressCase(Progress(i)))
                .append(Stream.emit(Event.CompletedCase(Completed(count.toLong)))),
            )
          )

      /** Counts the bytes it was handed, so a test can tell the request body actually reached the
        * impl rather than being consumed by input decoding.
        */
      def upload() =
        body =>
          IO.pure(
            (
              UploadOutput(),
              Stream.eval(body.compile.count).map(total => Event.CompletedCase(Completed(total))),
            )
          )

      /** Raises the error named by the path label, so each declared error's `@httpError` status can
        * be checked; `boom` raises an undeclared one, which must NOT be encoded here.
        */
      def fallible(which: String) =
        _ =>
          which match {
            case "missing"       => IO.raiseError(NotThere("no such thing"))
            case "unprocessable" => IO.raiseError(Unprocessable("cannot do that"))
            case "boom"          => IO.raiseError(new RuntimeException("undeclared"))
            case other           => IO.pure((FallibleOutput(other), Stream.empty))
          }

      /** Echoes the path label back, proving metadata is still decoded when the body is streamed.
        */
      def tagged(tag: String) =
        body =>
          IO.pure(
            (
              TaggedOutput(),
              Stream
                .eval(body.compile.count)
                .map(total => Event.ProgressCase(Progress(total)))
                .append(Stream.emit(Event.FailedCase(Failed(tag)))),
            )
          )

    }

  private val routes = NdjsonRestJsonBuilder.routes(impl)

  private def run(request: Request[IO]): IO[Response[IO]] =
    routes.run(request).value.map(_.getOrElse(Response.notFound[IO]))

  private def bodyText(response: Response[IO]): IO[String] =
    response.body.through(fs2.text.utf8.decode).compile.string

  private def lines(response: Response[IO]): IO[List[String]] =
    bodyText(response).map(_.linesIterator.toList)

  test("a non-streaming operation is routed like plain simpleRestJson") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/greet/world"))).flatMap { response =>
      bodyText(response).map { body =>
        expect(response.status == Status.Ok) &&
        expect(
          response.contentType.map(_.mediaType).contains(org.http4s.MediaType.application.json)
        ) &&
        expect(body == """{"message":"hello world"}""")
      }
    }
  }

  test("query parameters are decoded from metadata") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/greet/world?loud=true"))).flatMap { response =>
      bodyText(response).map(body => expect(body == """{"message":"HELLO world"}"""))
    }
  }

  test("a streamed output is framed as one JSON value per line") {
    run(
      Request[IO](Method.POST, Uri.unsafeFromString("/echo"))
        .withEntity("""{"count":3}""")
    ).flatMap { response =>
      lines(response).map { ls =>
        expect(response.status == Status.Ok) &&
        expect(response.contentType.map(_.mediaType).contains(Ndjson.mediaType)) &&
        expect(
          ls == List(
            """{"progress":{"soFar":0}}""",
            """{"progress":{"soFar":1}}""",
            """{"progress":{"soFar":2}}""",
            """{"completed":{"total":3}}""",
          )
        )
      }
    }
  }

  test("every NDJSON line is newline-terminated, so truncation is detectable") {
    run(
      Request[IO](Method.POST, Uri.unsafeFromString("/echo"))
        .withEntity("""{"count":2}""")
    ).flatMap { response =>
      bodyText(response).map(body => expect(body.endsWith("\n")))
    }
  }

  test("a single-element output stream is still framed as one terminated line") {
    run(
      Request[IO](Method.POST, Uri.unsafeFromString("/echo"))
        .withEntity("""{"count":0}""")
    ).flatMap { response =>
      bodyText(response).map { body =>
        expect(response.status == Status.Ok) &&
        expect(body == "{\"completed\":{\"total\":0}}\n")
      }
    }
  }

  test("a streamed request body reaches the implementation intact") {
    run(
      Request[IO](Method.POST, Uri.unsafeFromString("/upload"))
        .withEntity("hello world")
    ).flatMap { response =>
      lines(response).map { ls =>
        expect(response.status == Status.Ok) &&
        expect(ls == List("""{"completed":{"total":11}}"""))
      }
    }
  }

  test("metadata is decoded even when the body is claimed by the stream") {
    run(
      Request[IO](Method.POST, Uri.unsafeFromString("/tagged/abc"))
        .withEntity("1234")
    ).flatMap { response =>
      lines(response).map { ls =>
        // 202, per the operation's @http(code:) — the status comes from the model,
        // not a hardcoded 200.
        expect(response.status == Status.Accepted) &&
        expect(ls == List("""{"progress":{"soFar":4}}""", """{"failed":{"message":"abc"}}"""))
      }
    }
  }

  test("an unknown path falls through, so the routes can be combined with others") {
    routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/nope"))).value.map { response =>
      expect(response.isEmpty)
    }
  }

  test("a known path with the wrong method falls through") {
    routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/echo"))).value.map { response =>
      expect(response.isEmpty)
    }
  }

  test("a declared error is encoded with the status from its @httpError") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/fallible/missing"))).flatMap { response =>
      bodyText(response).map { body =>
        expect(response.status == Status.NotFound) &&
        expect(
          response.contentType.map(_.mediaType).contains(org.http4s.MediaType.application.json)
        ) &&
        expect(clue(body) == """{"message":"no such thing"}""")
      }
    }
  }

  // A second error on the same operation, with a different status — so the status is read per
  // error rather than per operation.
  test("each declared error keeps its own status") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/fallible/unprocessable"))).flatMap {
      response =>
        bodyText(response).map { body =>
          expect(response.status == Status.UnprocessableContent) &&
          expect(body == """{"message":"cannot do that"}""")
        }
    }
  }

  test("an undeclared failure propagates rather than being encoded as a typed error") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/fallible/boom")))
      .attempt
      .map(result => expect(result.isLeft))
  }

  test("a successful call on an operation that declares errors is unaffected") {
    run(Request[IO](Method.GET, Uri.unsafeFromString("/fallible/fine"))).flatMap { response =>
      bodyText(response).map { body =>
        expect(response.status == Status.Ok) && expect(body == """{"ok":"fine"}""")
      }
    }
  }

}
