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
import cats.data.OptionT
import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream
import org.http4s.Headers
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.headers.`Content-Type`
import smithy4s.Blob
import smithy4s.Endpoint
import smithy4s.PartialData
import smithy4s.Service
import smithy4s.http.CaseInsensitive
import smithy4s.http.HttpEndpoint
import smithy4s.http.HttpMethod
import smithy4s.http.HttpRestSchema
import smithy4s.http.HttpStatusCode
import smithy4s.http.Metadata
import smithy4s.http4s.ServerEndpointMiddleware
import smithy4s.json.Json
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.Alt
import smithy4s.schema.Primitive
import smithy4s.schema.Schema

/** Interprets a service annotated with `org.polyvariant.ndjson#ndjsonRestJson` as http4s routes.
  *
  * The counterpart to smithy4s's `SimpleRestJsonBuilder`, differing in exactly one respect: it
  * accepts a kind-5 algebra ([[WithStreamedIO]]) instead of a kind-1 `FunctorAlgebra`, so
  * `@streaming` members survive into the method signatures and can be wired to the request and
  * response bodies. Operations without any `@streaming` member are routed the same way
  * `simpleRestJson` would route them, so a service may freely mix both.
  *
  * ==Scope==
  *
  * A streaming response body is drained by the server after the handler that produced it has
  * returned. Middleware built on request-scoped state — an `IOLocal` holding the caller's identity,
  * a tracing span — has therefore already gone out of scope by the time the stream runs, even
  * though it was in scope when the operation started.
  *
  * So: resolve anything request-scoped in the `F[...]` that produces the stream, never from inside
  * the stream itself. That effect runs while the request scope is still live; the stream does not.
  * Reading a local lazily from within the stream does not fail — it silently observes whatever the
  * local was reset to — which is why this is stated here rather than left to be discovered.
  * `MiddlewareScopeTests` holds this property.
  *
  * This is inherent to streaming a response rather than an artifact of this builder: nothing an
  * interpreter or a middleware can do keeps a request scope open across a body that is pulled after
  * the response has been returned.
  */
object NdjsonRestJsonBuilder {

  /** Builds routes for `impl`, with `middleware` applied per endpoint.
    *
    * `middleware` is the only extension point, and it is deliberately the whole of it: gating
    * (roles, authentication), tracing and error mapping are all caller concerns applied on top,
    * reading whatever they need from the endpoint's own hints. The protocol itself knows about none
    * of them.
    *
    * It is a `ServerEndpointMiddleware[F]` — the same type `SimpleRestJsonBuilder` takes — so
    * middleware written for one builder works unchanged with the other. As there, it wraps the
    * handler of an endpoint that has ''already matched'', never the routing decision itself: a
    * request for an unknown path falls through untouched, so these routes compose with others.
    */
  def routes[Alg[_[_, _, _, _, _]], F[_]: Concurrent](
    impl: Alg[WithStreamedIO[F]],
    middleware: ServerEndpointMiddleware[F] = Endpoint.Middleware.noop[HttpApp[F]],
  )(
    using service: Service[Alg]
  ): HttpRoutes[F] = {
    val interpreter: PolyFunction5[service.Operation, WithStreamedIO[F]] =
      service.toPolyFunction(impl)

    // Pairing each endpoint with its HttpEndpoint inside a single helper keeps `I` bound: splitting
    // the cast from the use would let the existential escape and the two `I`s stop matching.
    def compile(
      endpoint: service.Endpoint[?, ?, ?, ?, ?]
    ): Option[(HttpEndpoint[?], HttpRoutes[F])] =
      HttpEndpoint
        .cast(endpoint.schema)
        .toOption
        .map { http =>
          (http, one(endpoint, http, interpreter, middleware.prepare(service)(endpoint)))
        }

    // `moreSpecific` puts static segments ahead of greedy labels, so an endpoint that could only
    // match by wildcard never shadows an exact one regardless of declaration order.
    service
      .endpoints
      .toList
      .flatMap(compile)
      .sortWith((left, right) => HttpEndpoint.moreSpecific(left._1, right._1))
      .map(_._2)
      .foldLeft(HttpRoutes.empty[F])(_ <+> _)
  }

  /** Routes a single endpoint: match method and path, decode, run, encode.
    *
    * `wrap` is applied to the handler only once the request has matched, so it sees an `HttpApp` —
    * a request that reaches it is one this endpoint has claimed.
    */
  private def one[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    http: HttpEndpoint[I],
    interpreter: PolyFunction5[Op, WithStreamedIO[F]],
    wrap: HttpApp[F] => HttpApp[F],
  ): HttpRoutes[F] = {
    val run: I => WithStreamedIO[F][I, E, O, SI, SO] =
      input => interpreter(endpoint.wrap(input))

    def handler(pathParams: Map[String, String]): HttpApp[F] =
      wrap(
        Kleisli { (request: Request[F]) =>
          val respond =
            for {
              input <- decodeInput(endpoint, pathParams, request)
              result <- run(input)(streamedBody(endpoint, request))
            } yield encodeOutput(endpoint, http, result._1, result._2)

          encodeErrors(endpoint, respond)
        }
      )

    Kleisli { (request: Request[F]) =>
      OptionT
        .fromOption[F](matchRequest(http, request))
        .semiflatMap(pathParams => handler(pathParams).run(request))
    }
  }

  /** Encodes an operation's declared errors (`errors: [...]`) as HTTP responses, taking each status
    * from its `@httpError` and falling back to 500.
    *
    * Only errors the operation declares are caught — `liftError` returns `None` for anything else,
    * which propagates so the surrounding middleware still turns it into a 500.
    *
    * This can only help a ''non-streaming'' failure. Once an operation has begun streaming, the
    * status is already committed and there is nowhere left to put an error; such failures have to
    * travel as a member of the output union instead (`failed`, by convention).
    */
  private def encodeErrors[Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    response: F[Response[F]],
  ): F[Response[F]] =
    endpoint
      .error
      .fold(response) { errorSchema =>
        val statusOf = HttpStatusCode.fromSchema(errorSchema.schema)

        // Encode through the *alternative's* schema, not the union's. `errorSchema.schema` is the
        // union of everything the operation declares, so encoding an error through it would wrap
        // the payload in a discriminator (`{"NotThere":{...}}`); the wire format is the member's
        // own body, as simpleRestJson writes it.
        val encoders = errorSchema
          .alternatives
          .map(alt => altEncoder(alt))

        response.recoverWith { throwable =>
          errorSchema
            .liftError(throwable)
            .fold(throwable.raiseError[F, Response[F]]) { error =>
              Response[F](
                status = Status
                  .fromInt(statusOf.code(error, 500))
                  .getOrElse(Status.InternalServerError),
                headers = Headers(`Content-Type`(MediaType.application.json)),
                body = Stream.chunk(
                  Chunk.array(encoders(errorSchema.ordinal(error)).apply(error).toArray)
                ),
              ).pure[F]
            }
        }
      }

  /** An encoder for one member of an error union, narrowing the union value to that member first.
    *
    * Split out so the existential member type stays bound to its own schema and projection.
    */
  private def altEncoder[E, A](alt: Alt[E, A]): E => Blob = {
    val encoder = Json.payloadCodecs.encoders.fromSchema(alt.schema)
    error => encoder.encode(alt.project(error))
  }

  private def matchRequest[F[_], I](
    http: HttpEndpoint[I],
    request: Request[F],
  ): Option[Map[String, String]] =
    Option
      .when(methodMatches(http.method, request))(())
      .flatMap(_ => http.matches(request.uri.path.segments.map(_.decoded()).toIndexedSeq))

  private def methodMatches[F[_]](method: HttpMethod, request: Request[F]): Boolean =
    method.showCapitalised.equalsIgnoreCase(request.method.name)

  /** The request body, as the operation's streamed-input element type; empty when it has none.
    *
    * A `@streaming blob` codegens to a `Newtype[Byte]` (`ArchiveBlob`, `Payload`, …) rather than to
    * `Byte` itself, so the raw body has to be wrapped element-wise. The wrapper is recovered from
    * the streaming member's own schema rather than assumed, so a shape that is not a blob fails
    * loudly here instead of corrupting the stream downstream.
    *
    * `SI` is `Nothing` when the operation streams nothing in, making the empty stream the only
    * inhabitant that could be passed.
    */
  private def streamedBody[Op[_, _, _, _, _], F[_], I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    request: Request[F],
  ): Stream[F, SI] =
    endpoint
      .streamedInput
      .fold(Stream.empty.covaryAll[F, SI]) { streamed =>
        val wrap = byteWrapper(streamed.schema)
        request.body.map(wrap)
      }

  /** Recovers the `Byte => SI` wrapper implied by a `@streaming blob`'s schema.
    *
    * Codegen renders such a blob as `bijection(byte, ...)`, so the wrapper is the bijection itself.
    * An operation whose streamed input is anything else is a protocol error, and is reported as one
    * rather than being coerced.
    *
    * The nested match on the primitive's own tag is what makes this typecheck without a cast:
    * `Primitive` is a GADT, so matching `PByte` refines the bijection's source type to `Byte`
    * within the branch. Asking `underlying.isPrimitive(PByte)` in a guard instead would answer the
    * same question at runtime while telling the compiler nothing, leaving the `Byte` to be forced
    * in by hand.
    */
  private def byteWrapper[SI](schema: Schema[SI]): Byte => SI =
    schema match {
      case Schema.BijectionSchema(Schema.PrimitiveSchema(_, _, Primitive.PByte), bijection) =>
        bijection.apply
      case other =>
        throw new IllegalArgumentException(
          s"${other.shapeId} is used as a streamed input but is not a blob; the " +
            "ndjsonRestJson protocol can only stream a `@streaming blob` request body in."
        )
    }

  /** Decodes the operation's input from metadata (path / query / headers) and, when the operation
    * doesn't stream its body in, the JSON body.
    *
    * An operation that DOES stream its body in gets its payload from [[streamedBody]] instead, so
    * only the metadata half is decoded here — reading the body to decode it would consume the very
    * bytes the impl is about to be handed.
    */
  private def decodeInput[Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    pathParams: Map[String, String],
    request: Request[F],
  ): F[I] = {
    val metadata = toMetadata(pathParams, request)

    def fromMetadata[A](schema: Schema[A]): F[A] =
      Metadata
        .decode(metadata)(Metadata.Decoder.fromSchema(schema))
        .liftTo[F]

    def fromBody[A](schema: Schema[A]): F[A] =
      request
        .body
        .compile
        .to(Array)
        .flatMap(bytes =>
          Json.payloadCodecs.decoders.fromSchema(schema).decode(Blob(bytes)).liftTo[F]
        )

    HttpRestSchema(endpoint.schema.input) match {
      case HttpRestSchema.Empty(value)      => value.pure[F]
      case HttpRestSchema.OnlyMetadata(sch) => fromMetadata(sch)
      case HttpRestSchema.OnlyBody(sch)     =>
        if (endpoint.streamedInput.isDefined)
          fromMetadata(endpoint.schema.input)
        else
          fromBody(sch)
      case HttpRestSchema.MetadataAndBody(metadataSchema, bodySchema) =>
        if (endpoint.streamedInput.isDefined)
          fromMetadata(metadataSchema).map(meta => PartialData.unsafeReconcile(meta))
        else
          (fromMetadata(metadataSchema), fromBody(bodySchema)).mapN((meta, body) =>
            PartialData.unsafeReconcile(meta, body)
          )
    }
  }

  private def toMetadata[F[_]](pathParams: Map[String, String], request: Request[F]): Metadata =
    Metadata(
      path = pathParams,
      // smithy4s models a valueless query param (`?flag`) as a None, which http4s's
      // `multiParams` has already flattened away; re-wrap so the shapes line up.
      query = request.uri.query.multiParams.map((k, vs) => (k, vs.map(Some(_)))),
      headers = request
        .headers
        .headers
        .groupBy(h => CaseInsensitive(h.name.toString))
        .map((name, hs) => (name, hs.map(_.value).toSeq)),
    )

  /** Encodes the response: NDJSON when the operation streams its output, plain JSON otherwise.
    *
    * In the streaming case the status is fixed by `@http(code:)` and committed before the first
    * element is pulled, which is what lets a client render progress — and why a later failure has
    * to travel as an element rather than a status.
    */
  private def encodeOutput[Op[_, _, _, _, _], F[_], I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    http: HttpEndpoint[I],
    output: O,
    stream: Stream[F, SO],
  ): Response[F] = {
    val status = Status.fromInt(http.code).getOrElse(Status.Ok)

    endpoint
      .streamedOutput
      .fold {
        val blob = Json.payloadCodecs.encoders.fromSchema(endpoint.schema.output).encode(output)
        Response[F](
          status = status,
          headers = Headers(`Content-Type`(MediaType.application.json)),
          body = Stream.chunk(Chunk.array(blob.toArray)),
        )
      } { streamed =>
        Response[F](
          status = status,
          headers = Headers(`Content-Type`(Ndjson.mediaType)),
          body = Ndjson.encode(stream, Json.payloadCodecs.encoders.fromSchema(streamed.schema)),
        )
      }
  }

}
