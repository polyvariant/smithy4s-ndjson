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

import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.Request
import org.http4s.Response
import org.polyvariant.ndjson.NdjsonRestJson
import smithy4s.Blob
import smithy4s.http.CaseInsensitive
import smithy4s.http.HttpMethod
import smithy4s.http.HttpRequest
import smithy4s.http.HttpResponse
import smithy4s.http.HttpUnaryServerCodecs
import smithy4s.http.Metadata
import smithy4s.http.PathParams
import smithy4s.http.amazonErrorTypeHeader
import smithy4s.http.errorTypeHeader
import smithy4s.http4s.kernel.fromSmithy4sHttpResponse
import smithy4s.http4s.kernel.toSmithy4sHttpUri
import smithy4s.interopcats.given
import smithy4s.json.Json
import smithy4s.server.UnaryServerCodecs

/** The non-streaming half of the protocol: input decoding, output encoding and typed-error
  * encoding, delegated wholesale to smithy4s's own `HttpUnaryServerCodecs`.
  *
  * This is deliberately not hand-written. `ndjsonRestJson` promises that metadata bindings and
  * non-streaming payloads follow `simpleRestJson` exactly, and the only way to keep that promise
  * across smithy4s versions is to run the same codec stack `SimpleRestJsonBuilder` runs.
  * Re-implementing it drifts: `@httpHeader` bindings on outputs and errors get dropped, the
  * error-type discriminator header goes missing, and the protocol's hint mask stops being applied.
  *
  * The one thing not shared with `SimpleRestJsonBuilder` is the hint mask itself, which comes from
  * ''this'' protocol's `traits` list rather than `simpleRestJson`'s — that list is what the trait
  * declares it interprets, so it is the honest mask for shapes served here.
  */
private object NdjsonRestJsonCodecs {

  private val jsonCodecs = Json
    .payloadCodecs
    .configureJsoniterCodecCompiler(_.withHintMask(NdjsonRestJson.protocol.hintMask))

  val encoders = jsonCodecs.encoders

  /** smithy4s clients discriminate a union of declared errors by header before falling back to the
    * body, so both spellings are sent — `X-Amzn-Errortype` is what Amazon-issued generators read.
    */
  private val errorHeaders = List(errorTypeHeader, amazonErrorTypeHeader)

  /** The headers, in the shape smithy4s's metadata decoding expects.
    *
    * The kernel has this conversion too, but as `private[smithy4s]`; only the request/response and
    * URI conversions are public. It is three lines, so it is repeated here rather than reached for.
    */
  def headersOf[F[_]](request: Request[F]): Map[CaseInsensitive, Seq[String]] =
    request
      .headers
      .headers
      .groupBy(header => CaseInsensitive(header.name.toString))
      .map((name, hs) => (name, hs.map(_.value)))

  /** The request as smithy4s sees it, with the path parameters this endpoint matched.
    *
    * Not `kernel.toSmithy4sHttpRequest`: that reads its path params from a request attribute keyed
    * by `kernel.pathParamsKey`, which is `private[smithy4s]` and so cannot be set from here. The
    * public `toSmithy4sHttpUri` takes them as an argument instead, which is all this needs.
    */
  private def toHttpRequest[F[_]: Concurrent](
    request: Request[F],
    pathParams: PathParams,
  ): F[HttpRequest[Blob]] =
    request
      .body
      .compile
      .to(Array)
      .map(bytes =>
        HttpRequest(
          method = smithy4sMethod(request),
          uri = toSmithy4sHttpUri(request.uri, Some(pathParams)),
          headers = headersOf(request),
          body = Blob(bytes),
        )
      )

  /** `kernel.toSmithy4sHttpMethod` is `private[smithy4s]` too; the mapping is total and small. */
  private def smithy4sMethod[F[_]](request: Request[F]): HttpMethod =
    HttpMethod.fromStringOrDefault(request.method.name.toUpperCase)

  /** The full server codec stack, compiled per operation.
    *
    * `withBaseResponse` seeds a 200 that `@http(code:)` and `@httpResponseCode` then override. The
    * request carries its matched path parameters alongside it, because they are decided by routing
    * — which happens before this — and there is no public way to smuggle them through the request
    * itself.
    */
  def make[F[_]: Concurrent]: UnaryServerCodecs.Make[F, (Request[F], PathParams), Response[F]] =
    HttpUnaryServerCodecs
      .builder[F]
      .withBodyDecoders(jsonCodecs.decoders)
      .withSuccessBodyEncoders(encoders)
      .withErrorBodyEncoders(encoders)
      .withErrorTypeHeaders(errorHeaders*)
      .withMetadataDecoders(Metadata.Decoder)
      .withMetadataEncoders(Metadata.Encoder)
      .withBaseResponse(_ => HttpResponse(200, Map.empty, Blob.empty).pure[F])
      .withResponseMediaType("application/json")
      .withWriteEmptyStructs(!_.isUnit)
      .withRequestTransformation[(Request[F], PathParams)](toHttpRequest(_, _))
      .withResponseTransformation(fromSmithy4sHttpResponse[F](_).pure[F])
      .build()

}
