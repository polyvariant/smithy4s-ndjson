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
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import smithy4s.Endpoint
import smithy4s.Service
import smithy4s.http.HttpEndpoint
import smithy4s.http.HttpMethod
import smithy4s.http.Metadata
import smithy4s.http.PathParams
import smithy4s.http4s.ServerEndpointMiddleware
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.Primitive
import smithy4s.schema.Schema
import smithy4s.server.UnaryServerCodecs

/** Interprets a service annotated with `org.polyvariant.ndjson#ndjsonRestJson` as http4s routes.
  *
  * The counterpart to smithy4s's `SimpleRestJsonBuilder`, differing in exactly one respect: it
  * accepts a kind-5 algebra ([[WithStreamedIO]]) instead of a kind-1 `FunctorAlgebra`, so
  * `@streaming` members survive into the method signatures and can be wired to the request and
  * response bodies. Operations without any `@streaming` member are routed the same way
  * `simpleRestJson` would route them, so a service may freely mix both.
  *
  * ==What is delegated==
  *
  * Everything that isn't streaming comes from smithy4s itself, via [[NdjsonRestJsonCodecs]]:
  * decoding inputs, encoding outputs, and encoding declared errors all run the same codec stack
  * `SimpleRestJsonBuilder` runs. Only routing and the two streaming edges are implemented here,
  * because those are the parts smithy4s's own router cannot express — it takes a kind-1 algebra,
  * which has already erased `SI` and `SO` by the time it sees the service.
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
    * `middleware` is the only extension point, and it is deliberately the whole of it. Whatever a
    * caller needs to wrap around an operation — tracing, metrics, error mapping, authorization,
    * rate limiting — goes here, reading whatever it needs from the endpoint's own hints. The
    * protocol itself knows about none of them.
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

    val codecs = NdjsonRestJsonCodecs.make[F]

    // Pairing each endpoint with its HttpEndpoint inside a single helper keeps `I` bound: splitting
    // the cast from the use would let the existential escape and the two `I`s stop matching.
    def compile(
      endpoint: service.Endpoint[?, ?, ?, ?, ?]
    ): Option[(HttpEndpoint[?], HttpRoutes[F])] =
      HttpEndpoint
        .cast(endpoint.schema)
        .toOption
        .map { http =>
          (http, one(endpoint, http, interpreter, codecs, middleware.prepare(service)(endpoint)))
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
    codecs: UnaryServerCodecs.Make[F, (Request[F], PathParams), Response[F]],
    wrap: HttpApp[F] => HttpApp[F],
  ): HttpRoutes[F] = {
    val run: I => WithStreamedIO[F][I, E, O, SI, SO] =
      input => interpreter(endpoint.wrap(input))

    val operationCodecs = codecs(endpoint.schema)

    val decode = decodeInput(endpoint, operationCodecs)
    val encode = encodeOutput(endpoint, http, operationCodecs)

    def handler(pathParams: PathParams): HttpApp[F] =
      wrap(
        Kleisli { (request: Request[F]) =>
          val respond =
            for {
              input <- decode(request, pathParams)
              result <- run(input)(streamedBody(endpoint, request))
              response <- encode(result._1, result._2)
            } yield response

          encodeErrors(endpoint, operationCodecs, respond)
        }
      )

    Kleisli { (request: Request[F]) =>
      OptionT
        .fromOption[F](matchRequest(http, request))
        .semiflatMap(pathParams => handler(pathParams).run(request))
    }
  }

  /** Encodes an operation's declared errors (`errors: [...]`) as HTTP responses.
    *
    * The encoding itself is smithy4s's — status from `@httpError`, body and any `@httpHeader`
    * bindings from the error's own schema, plus the error-type discriminator header — but which
    * throwables it applies to is decided here rather than by the codecs' `throwableEncoder`.
    * `liftError` returns `None` for anything the operation does not declare, and those propagate
    * untouched so the surrounding middleware still sees them and can turn them into a 500 itself.
    * Swallowing them into a canned 500 here would hide server faults from exactly the layer that
    * exists to observe them.
    *
    * This can only help a ''non-streaming'' failure. Once an operation has begun streaming, the
    * status is already committed and there is nowhere left to put an error; such failures have to
    * travel as a member of the output union instead (`failed`, by convention).
    */
  private def encodeErrors[Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    codecs: UnaryServerCodecs[F, (Request[F], PathParams), Response[F], I, E, O],
    response: F[Response[F]],
  ): F[Response[F]] =
    endpoint
      .error
      .fold(response) { errorSchema =>
        response.recoverWith { throwable =>
          errorSchema
            .liftError(throwable)
            .fold(throwable.raiseError[F, Response[F]])(codecs.errorEncoder)
        }
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

  /** Decodes the operation's input.
    *
    * Ordinarily this is smithy4s's own decoder, which reads metadata and body together exactly as
    * `simpleRestJson` does. An operation that streams its body in cannot use it: that decoder
    * consumes the request body to decode the payload, and those are the very bytes the impl is
    * about to be handed. Such operations decode from metadata alone; the streamed payload member
    * has no metadata binding, so it is simply absent from what the metadata decoder reads.
    */
  private def decodeInput[Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    codecs: UnaryServerCodecs[F, (Request[F], PathParams), Response[F], I, E, O],
  ): (Request[F], PathParams) => F[I] =
    if (endpoint.streamedInput.isEmpty)
      (request, pathParams) => codecs.inputDecoder((request, pathParams))
    else {
      val decoder = Metadata.Decoder.fromSchema(endpoint.schema.input)

      (request, pathParams) => Metadata.decode(metadataOf(request, pathParams))(decoder).liftTo[F]
    }

  /** The request's metadata, with the path parameters this endpoint matched.
    *
    * Only needed on the streamed-input path — everywhere else smithy4s's own decoder builds this
    * itself, from the same pieces.
    */
  private def metadataOf[F[_]](request: Request[F], pathParams: PathParams): Metadata =
    Metadata(
      path = pathParams,
      // smithy4s models a valueless query param (`?flag`) as a None, which http4s's
      // `multiParams` has already flattened away; re-wrap so the shapes line up.
      query = request.uri.query.multiParams.map((k, vs) => (k, vs.map(Some(_)))),
      headers = NdjsonRestJsonCodecs.headersOf(request),
    )

  /** Encodes the response: NDJSON when the operation streams its output, plain JSON otherwise.
    *
    * The non-streaming case is smithy4s's own encoder, so `@httpResponseCode` and `@httpHeader`
    * bindings on the output are honoured. The streaming case cannot use it — that encoder wants a
    * whole body up front — so the envelope's metadata is encoded separately and the body is framed
    * as NDJSON. The status is fixed by `@http(code:)` and committed before the first element is
    * pulled, which is what lets a client render progress, and why a later failure has to travel as
    * an element rather than a status.
    */
  private def encodeOutput[Op[_, _, _, _, _], F[_]: Concurrent, I, E, O, SI, SO](
    endpoint: Endpoint[Op, I, E, O, SI, SO],
    http: HttpEndpoint[I],
    codecs: UnaryServerCodecs[F, (Request[F], PathParams), Response[F], I, E, O],
  ): (O, Stream[F, SO]) => F[Response[F]] =
    endpoint
      .streamedOutput
      .fold[(O, Stream[F, SO]) => F[Response[F]]]((output, _) => codecs.outputEncoder(output)) {
        streamed =>
          val status = Status.fromInt(http.code).getOrElse(Status.Ok)
          val metadataEncoder = Metadata.Encoder.fromSchema(endpoint.schema.output)
          val elementEncoder = NdjsonRestJsonCodecs.encoders.fromSchema(streamed.schema)

          (output, stream) => {
            val metadata = metadataEncoder.encode(output)

            Response[F](
              status = metadata.statusCode.flatMap(Status.fromInt(_).toOption).getOrElse(status),
              headers = ndjsonHeaders(metadata),
              body = Ndjson.encode(stream, elementEncoder),
            ).pure[F]
          }
      }

  /** The response headers for a streamed output: whatever the envelope's `@httpHeader` members
    * bind, plus the NDJSON content type.
    *
    * The content type is `put` last so it wins: the framing is the protocol's to decide, not the
    * envelope's.
    */
  private def ndjsonHeaders(metadata: Metadata): Headers =
    Headers(
      metadata
        .headers
        .toList
        .flatMap((name, values) => values.map(value => Header.Raw(CIString(name.toString), value)))
    ).put(`Content-Type`(Ndjson.mediaType))

}
