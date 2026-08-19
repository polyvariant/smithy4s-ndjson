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

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.IOLocal
import cats.effect.Ref
import fs2.Stream
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.polyvariant.ndjson.test.*
import smithy4s.http4s.ServerEndpointMiddleware
import weaver.SimpleIOSuite

/** Pins down where request-scoped state is, and is not, still available — the one property a
  * streaming protocol has that a unary one doesn't, and the one most likely to bite a caller.
  *
  * A response body is drained by the server after the handler has returned, so an `IOLocal` set by
  * middleware is already out of scope by then. These tests record the value at both points and
  * assert on the difference, so a change that alters the answer fails here rather than silently in
  * whatever a caller reads that state for.
  *
  * The observation itself goes through a `Ref`, not an `IOLocal`: locals are fiber-local, so a
  * write made inside the response stream would not be visible to the asserting fiber at all.
  */
object MiddlewareScopeTests extends SimpleIOSuite {

  private def routesFor(
    local: IOLocal[String],
    observed: Ref[IO, (String, String)],
  ): HttpRoutes[IO] = {
    val impl: TestServiceGen[WithStreamedIO[IO]] =
      new TestServiceGen[WithStreamedIO[IO]] {
        def greet(name: String, loud: Option[Boolean], caller: Option[String]) =
          _ => IO.pure((GreetOutput(name), Stream.empty))

        def echo(count: Int) =
          _ =>
            // Read once eagerly (request scope) and once from within the stream (drain scope),
            // then record the pair so the test can compare them.
            local.get.map { atStart =>
              (
                EchoOutput(),
                Stream
                  .eval(local.get.flatTap(atDrain => observed.set((atStart, atDrain))))
                  .map(_ => Event.ProgressCase(Progress(1L))),
              )
            }

        def fallible(which: String) = _ => IO.pure((FallibleOutput("ok"), Stream.empty))
        def tagged(tag: String, source: Option[String]) =
          _ => IO.pure((TaggedOutput(), Stream.empty))
        def upload() = _ => IO.pure((UploadOutput(), Stream.empty))
        def ingest() = _ => IO.pure((IngestOutput(0), Stream.empty))
        def download(name: String) = _ => IO.pure((DownloadOutput(), Stream.empty))
        def relay() = _ => IO.pure((RelayOutput(), Stream.empty))
        def formats(stamp: smithy4s.time.Timestamp, renamed: String) =
          _ => IO.pure((FormatsOutput(), Stream.empty))
      }

    // Stands in for any request-scoped middleware: sets a value, then clears it once the handler
    // returns — exactly what an `IOLocal`-based scope does at the end of a request.
    val scoped: ServerEndpointMiddleware[IO] =
      new ServerEndpointMiddleware.Simple[IO] {
        def prepareWithHints(
          serviceHints: smithy4s.Hints,
          endpointHints: smithy4s.Hints,
        ): HttpApp[IO] => HttpApp[IO] =
          app =>
            Kleisli { request =>
              local.set("in-scope") *> app.run(request) <* local.set("out-of-scope")
            }
      }

    NdjsonRestJsonBuilder.routes(impl, scoped)
  }

  test("state set by middleware is visible while the operation starts") {
    for {
      local <- IOLocal("initial")
      observed <- Ref[IO].of(("", ""))
      routes = routesFor(local, observed)
      response <- routes
        .run(Request[IO](Method.POST, Uri.unsafeFromString("/echo")).withEntity("""{"count":1}"""))
        .value
        .map(_.getOrElse(Response.notFound[IO]))
      _ <- response.body.compile.drain
      seen <- observed.get
    } yield expect(response.status == Status.Ok) && expect(clue(seen)._1 == "in-scope")
  }

  // The load-bearing one. An implementation that reads request-scoped state lazily — inside the
  // stream rather than while producing it — reads it after the scope has closed, and gets whatever
  // the local was reset to instead of an error. Callers must resolve such state eagerly; this test
  // is what makes that a checked property rather than a comment.
  test("state set by middleware is GONE by the time the response stream is drained") {
    for {
      local <- IOLocal("initial")
      observed <- Ref[IO].of(("", ""))
      routes = routesFor(local, observed)
      response <- routes
        .run(Request[IO](Method.POST, Uri.unsafeFromString("/echo")).withEntity("""{"count":1}"""))
        .value
        .map(_.getOrElse(Response.notFound[IO]))
      _ <- response.body.compile.drain
      seen <- observed.get
    } yield expect(clue(seen)._1 == "in-scope") && expect(clue(seen)._2 == "out-of-scope")
  }

}
