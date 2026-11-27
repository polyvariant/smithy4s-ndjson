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

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.polyvariant.ndjson.NdjsonRestJson
import org.polyvariant.ndjson.http4s.NdjsonRestJsonBuilder
import org.polyvariant.ndjson.http4s.WithStreamedIO
import org.typelevel.ci.CIString
import smithy.test.HttpRequestTestCase
import smithy.test.HttpRequestTests
import smithy.test.HttpResponseTestCase
import smithy.test.HttpResponseTests
import smithy4s.Bijection
import smithy4s.Document
import smithy4s.Service
import smithy4s.ShapeTag
import smithy4s.compliancetests.ComplianceTest
import smithy4s.compliancetests.HttpProtocolCompliance
import smithy4s.compliancetests.Router
import smithy4s.json.Json
import smithy4s.kinds.FunctorAlgebra
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.Schema
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.protocoltests.traits.eventstream.Event
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestCase
import software.amazon.smithy.protocoltests.traits.eventstream.EventType

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Runs the compliance cases as the server: `NdjsonRestJsonBuilder` serving
  * [[NdjsonRestJsonCompliance]], with an implementation that records what it was handed and answers
  * with what the case says.
  *
  * A case's result is what didn't match, so empty means it passed.
  */
object ServerHarness {

  private val service = NdjsonRestJsonCompliance

  private type Endpoint[I, E, O, SI, SO] = service.Endpoint[I, E, O, SI, SO]

  // --- Operations that stream nothing: smithy4s's own harness ---

  /** smithy4s's server harness, which is what it checks `simpleRestJson` with. It hands its router
    * a kind-1 algebra, which can't stream, so that is adapted here into one whose streams are
    * always empty.
    */
  private val router: Router[IO] =
    new Router[IO] {
      type Protocol = NdjsonRestJson
      val protocolTag: ShapeTag[NdjsonRestJson] = NdjsonRestJson

      def routes[Alg[_[_, _, _, _, _]]](
        alg: FunctorAlgebra[Alg, IO]
      )(
        implicit
        service: Service[Alg]
      ): Resource[IO, HttpRoutes[IO]] = {
        val unary = service.toPolyFunction(alg)
        val streaming = service.fromPolyFunction[WithStreamedIO[IO]](
          new PolyFunction5[service.Operation, WithStreamedIO[IO]] {
            def apply[I, E, O, SI, SO](op: service.Operation[I, E, O, SI, SO])
              : Stream[IO, SI] => IO[(O, Stream[IO, SO])] =
              _ => unary(op).map(output => (output, Stream.empty))
          }
        )
        Resource.pure(
          NdjsonRestJsonBuilder.routes(streaming)(
            using summon,
            service,
          )
        )
      }

    }

  /** Every `@httpRequestTests` / `@httpResponseTests` case smithy4s's harness can judge.
    *
    * That leaves out an operation's own cases when it streams a blob: the harness never sees the
    * stream, so it would pass a case whatever the body. Those run in [[blobCases]] instead. A
    * declared error's cases stay even then — the harness answers them through an endpoint of its
    * own that doesn't stream, which is exactly how an error raised before a stream is encoded.
    */
  val smithy4sCases: List[ComplianceTest[IO]] = {
    val streamedCaseIds =
      service
        .endpoints
        .toList
        .filter(e => e.streamedInput.isDefined || e.streamedOutput.isDefined)
        .flatMap { e =>
          e.hints.get(HttpRequestTests).toList.flatMap(_.value.map(_.id)) ++
            e.hints.get(HttpResponseTests).toList.flatMap(_.value.map(_.id))
        }
        .toSet

    HttpProtocolCompliance.serverTests(router, service).filterNot(t => streamedCaseIds(t.id))
  }

  // --- @streaming blob: the body verbatim ---

  /** An operation's own `@httpRequestTests` / `@httpResponseTests` cases, where it streams a blob.
    */
  def blobCases(model: Model): List[(String, IO[List[String]])] =
    service.endpoints.toList.flatMap(endpointBlobCases(model, _))

  private def endpointBlobCases[I, E, O, SI, SO](model: Model, endpoint: Endpoint[I, E, O, SI, SO])
    : List[(String, IO[List[String]])] = {
    val op = model.expectShape(ShapeId.from(endpoint.id.show), classOf[OperationShape])
    def member(structure: ShapeId): String =
      CaseModel.streamedMember(model, structure).fold("")(_.getMemberName)
    val requests = endpoint.streamedInput.toList.flatMap { _ =>
      endpoint
        .hints
        .get(HttpRequestTests)
        .toList
        .flatMap(_.value)
        .filter(_.protocol == NdjsonRestJson.id)
        .map(tc =>
          s"${endpoint.name}(server|request): ${tc.id}" ->
            blobRequest(endpoint, member(op.getInputShape), tc)
        )
    }
    val responses = endpoint.streamedOutput.toList.flatMap { _ =>
      endpoint
        .hints
        .get(HttpResponseTests)
        .toList
        .flatMap(_.value)
        .filter(_.protocol == NdjsonRestJson.id)
        .map(tc =>
          s"${endpoint.name}(server|response): ${tc.id}" ->
            blobResponse(endpoint, member(op.getOutputShape), tc)
        )
    }
    requests ++ responses
  }

  /** Sends the case's request, and checks the operation was handed the input and the bytes its
    * `params` describe.
    */
  private def blobRequest[I, E, O, SI, SO](
    endpoint: Endpoint[I, E, O, SI, SO],
    streamedMember: String,
    tc: HttpRequestTestCase,
  ): IO[List[String]] = {
    val params = tc.params.getOrElse(Document.obj())
    for {
      streamed <- endpoint.streamedInput.liftTo[IO](new Exception("no streamed input"))
      bytes <- byteBijection(streamed.schema).liftTo[IO]
      expectedInput <- decode(endpoint.input, params).liftTo[IO]
      expectedBytes = blobParam(params, streamedMember)
      seen <- Ref[IO].of(Option.empty[(I, List[Byte])])
      request <- httpRequest(
        tc.method,
        tc.uri,
        tc.queryParams.getOrElse(Nil),
        tc.headers.getOrElse(Map.empty),
        tc.body.getOrElse("").getBytes(UTF_8),
      )
      response <-
        serve(endpoint) { (input, in) =>
          for {
            received <- in.map(bytes.from).compile.toList
            _ <- seen.set(Some((input, received)))
            output <- decode(endpoint.output, Document.obj()).liftTo[IO]
          } yield (output, Stream.empty)
        }(request)
      recorded <- seen.get
    } yield recorded match {
      case None => List(s"the operation wasn't called; the response was ${response.status}")
      case Some((input, received)) =>
        mismatch("input", input, expectedInput) ++
          mismatch("body", text(received), text(expectedBytes))
    }
  }

  /** Answers with the case's `params`, and checks the response's status, headers and bytes.
    *
    * A response case states no request, so one is made up that routes to the operation, with every
    * label in its URI set to `x`.
    */
  private def blobResponse[I, E, O, SI, SO](
    endpoint: Endpoint[I, E, O, SI, SO],
    streamedMember: String,
    tc: HttpResponseTestCase,
  ): IO[List[String]] = {
    val params = tc.params.getOrElse(Document.obj())
    for {
      streamed <- endpoint.streamedOutput.liftTo[IO](new Exception("no streamed output"))
      bytes <- byteBijection(streamed.schema).liftTo[IO]
      output <- decode(endpoint.output, params).liftTo[IO]
      http <- endpoint.hints.get(smithy.api.Http).liftTo[IO](new Exception("no @http"))
      request <- httpRequest(
        http.method.value,
        http.uri.value.replaceAll("\\{[^}]+\\}", "x"),
        Nil,
        Map.empty,
        Array.emptyByteArray,
      )
      response <-
        serve(endpoint) { (_, _) =>
          IO.pure((output, Stream.emits(blobParam(params, streamedMember)).map(bytes.to)))
        }(request)
      body <- response.body.compile.to(Array)
    } yield mismatch("status", response.status.code, tc.code) ++
      headerMismatches(response, tc.headers.getOrElse(Map.empty)) ++
      tc.body.toList.flatMap(expected => mismatch("body", new String(body, UTF_8), expected))
  }

  /** A blob's member in `params`: its text, as Smithy writes a blob in a case. */
  private def blobParam(params: Document, member: String): List[Byte] =
    params match {
      case Document.DObject(fields) =>
        fields.get(member).collect { case Document.DString(s) => s.getBytes(UTF_8).toList }.orEmpty
      case _ => Nil
    }

  /** A `@streaming blob` is generated as a newtype over `Byte`; this is that newtype's bijection.
    */
  private def byteBijection[A](schema: Schema[A]): Either[Exception, Bijection[Byte, A]] =
    schema match {
      case Schema.BijectionSchema(
            Schema.PrimitiveSchema(_, _, smithy4s.schema.Primitive.PByte),
            b,
          ) =>
        Right(b)
      case _ => Left(new Exception(s"not a streamed blob: ${schema.shapeId}"))
    }

  // --- @streaming union: @eventStreamTests ---

  /** Every `@eventStreamTests` case a server runs. */
  def eventStreamCases(model: Model): List[(String, IO[List[String]])] =
    CaseModel.serverEventStreamCases(model).flatMap { (op, tc) =>
      service
        .endpoints
        .find(_.id.name == op.getId.getName)
        .map(endpoint =>
          s"${endpoint.name}(server|events): ${tc.getId}" -> eventStream(model, op, endpoint, tc)
        )
    }

  /** Sends `initialRequest` with the `request` events as its body, answers with
    * `initialResponseParams` and the `response` events' `params`, and checks both ends: that the
    * operation was handed what the case says, and that the response is `initialResponse` with the
    * `response` events as its body.
    *
    * The operation reads its whole request stream before it answers, so a case expected to fail is
    * answered with an error — whose status is then checked against `initialResponse`.
    */
  private def eventStream[I, E, O, SI, SO](
    model: Model,
    op: OperationShape,
    endpoint: Endpoint[I, E, O, SI, SO],
    tc: EventStreamTestCase,
  ): IO[List[String]] = {
    val requestEvents = tc.getEvents.asScala.toList.filter(_.getType == EventType.REQUEST)
    val responseEvents = tc.getEvents.asScala.toList.filter(_.getType == EventType.RESPONSE)
    val inputShape = model.expectShape(op.getInputShape, classOf[StructureShape])
    val outputShape = model.expectShape(op.getOutputShape, classOf[StructureShape])
    val initialRequest = tc.getInitialRequest.toScala.getOrElse(Node.objectNode())
    val initialResponse = tc.getInitialResponse.toScala.getOrElse(Node.objectNode())
    val failing = tc.getExpectation.isFailure

    def params(node: Option[ObjectNode], shape: StructureShape): Document =
      CaseModel.paramsDocument(model, node.getOrElse(Node.objectNode()), shape)

    /** Each event's values, decoded with the streamed member's schema. */
    def values[A](
      streamed: smithy4s.schema.StreamingSchema[A],
      events: List[Event],
      shape: StructureShape,
    ): Either[Exception, List[A]] = {
      val target =
        CaseModel
          .streamedMember(model, shape.getId)
          .fold[Shape](shape)(m => model.expectShape(m.getTarget))
      events.flatMap(_.getParams.toScala).traverse { p =>
        decode(streamed.schema, CaseModel.paramsDocument(model, p, target))
      }
    }

    val inFraming = endpoint.streamedInput.map(s => Framing.of(s.schema))
    val outFraming = endpoint.streamedOutput.map(s => Framing.of(s.schema))

    val requestBody: Array[Byte] =
      inFraming.fold(stringMember(initialRequest, "body").getOrElse("").getBytes(UTF_8)) { framing =>
        requestEvents.toArray.flatMap { e =>
          e.getBytes
            .toScala
            .getOrElse(framing.frame(e.getBody.toScala.getOrElse("")).getBytes(UTF_8))
        }
      }

    for {
      expectedInput <- decode(
        endpoint.input,
        params(tc.getInitialRequestParams.toScala, inputShape),
      )
        .liftTo[IO]
      expectedIn <- endpoint
        .streamedInput
        .traverse(values(_, requestEvents, inputShape))
        .map(_.orEmpty)
        .liftTo[IO]
      out <- endpoint
        .streamedOutput
        .traverse(values(_, responseEvents, outputShape))
        .map(_.orEmpty)
        .liftTo[IO]
      seen <- Ref[IO].of(Option.empty[(I, List[SI])])
      request <- httpRequest(
        stringMember(initialRequest, "method").getOrElse("POST"),
        stringMember(initialRequest, "uri").getOrElse("/"),
        stringList(initialRequest, "queryParams"),
        stringMap(initialRequest, "headers"),
        requestBody,
      )
      response <-
        serve(endpoint) { (input, in) =>
          for {
            received <- in.compile.toList
            _ <- seen.set(Some((input, received)))
            output <- decode(
              endpoint.output,
              params(tc.getInitialResponseParams.toScala, outputShape),
            )
              .liftTo[IO]
          } yield (output, Stream.emits(out))
        }(request)
      body <- response.body.compile.to(Array).map(new String(_, UTF_8))
      recorded <- seen.get
    } yield {
      val expectedCode = initialResponse.getNumberMember("code").toScala.map(_.getValue.intValue)
      val status = expectedCode.toList.flatMap(mismatch("status", response.status.code, _))

      if (failing)
        status ++ Option.when(response.status.isSuccess)(
          s"expected a failure, got ${response.status}"
        )
      else {
        val handed =
          recorded match {
            case None =>
              List(s"the operation wasn't called; the response was ${response.status}: $body")
            case Some((input, received)) =>
              mismatch("input", input, expectedInput) ++ mismatch(
                "streamed input",
                received,
                expectedIn,
              )
          }
        val written =
          outFraming.fold(
            stringMember(initialResponse, "body").toList.flatMap(jsonMismatch("body", body, _))
          )(framing => eventMismatches(framing, body, responseEvents))
        handed ++ status ++ headerMismatches(
          response,
          stringMap(initialResponse, "headers"),
        ) ++ written
      }
    }
  }

  /** The written `body`, split into events, against the `response` events: a modeled event's data
    * compared as JSON, an unmodeled one's (the terminator) as text.
    */
  private def eventMismatches(framing: Framing, body: String, events: List[Event]): List[String] =
    framing.split(body) match {
      case Left(problem)  => List(problem)
      case Right(written) =>
        val expected = events.map(e => (e.getBody.toScala.getOrElse(""), e.getParams.isPresent))
        if (written.size != expected.size)
          List(
            s"wrote ${written.size} events, expected ${expected.size}: ${body.replace("\n", "\\n")}"
          )
        else
          written.zip(expected).flatMap {
            case (actual, (data, true))  => jsonMismatch("event", actual, data)
            case (actual, (data, false)) => mismatch("event", actual, data)
          }
    }

  // --- Plumbing ---

  /** Serves one request with `NdjsonRestJsonBuilder`, routing it to `endpoint`'s operation, whose
    * implementation is `run`. Any other operation being called is a failure.
    */
  private def serve[I, E, O, SI, SO](
    endpoint: Endpoint[I, E, O, SI, SO]
  )(
    run: (I, Stream[IO, SI]) => IO[(O, Stream[IO, SO])]
  ): Request[IO] => IO[Response[IO]] = {
    val impl = service.fromPolyFunction[WithStreamedIO[IO]](
      new PolyFunction5[service.Operation, WithStreamedIO[IO]] {
        def apply[I_, E_, O_, SI_, SO_](op: service.Operation[I_, E_, O_, SI_, SO_])
          : Stream[IO, SI_] => IO[(O_, Stream[IO, SO_])] = {
          val called = service.endpoint(op).id
          if (called == endpoint.id)
            in =>
              run(service.input(op).asInstanceOf[I], in.asInstanceOf[Stream[IO, SI]])
                .asInstanceOf[IO[(O_, Stream[IO, SO_])]]
          else
            _ => IO.raiseError(new Exception(s"routed to $called, expected ${endpoint.id}"))
        }
      }
    )
    NdjsonRestJsonBuilder.routes(impl).orNotFound.run
  }

  private def httpRequest(
    method: String,
    uri: String,
    query: List[String],
    headers: Map[String, String],
    body: Array[Byte],
  ): IO[Request[IO]] =
    (
      Method.fromString(method).liftTo[IO],
      Uri
        .fromString(
          if (query.isEmpty)
            uri
          else
            s"$uri?${query.mkString("&")}"
        )
        .liftTo[IO],
    ).mapN { (m, u) =>
      Request[IO](
        method = m,
        uri = u,
        headers = Headers(headers.toList.map((k, v) => Header.Raw(CIString(k), v))),
        body = Stream.emits(body),
      )
    }

  private def decode[A](schema: Schema[A], params: Document): Either[Exception, A] =
    ParamsDecoder
      .decode(schema, params)
      .leftMap(e => new Exception(s"params don't decode as ${schema.shapeId}: $e"))

  private def headerMismatches(response: Response[IO], expected: Map[String, String])
    : List[String] =
    expected.toList.flatMap { (name, value) =>
      val actual = response.headers.get(CIString(name)).map(_.head.value)
      Option.when(!actual.contains(value))(
        s"header $name: ${actual.getOrElse("absent")}, expected $value"
      )
    }

  private def mismatch[A](what: String, actual: A, expected: A): List[String] =
    Option.when(actual != expected)(s"$what: $actual, expected $expected").toList

  private def jsonMismatch(what: String, actual: String, expected: String): List[String] =
    (Json.readDocument(actual), Json.readDocument(expected)) match {
      case (Right(a), Right(e)) => Option.when(a != e)(s"$what: $actual, expected $expected").toList
      case _                    => mismatch(what, actual, expected)
    }

  private def text(bytes: List[Byte]): String = new String(bytes.toArray, UTF_8)

  private def stringMember(node: ObjectNode, name: String): Option[String] =
    node.getStringMember(name).toScala.map(_.getValue)

  private def stringList(node: ObjectNode, name: String): List[String] =
    node
      .getArrayMember(name)
      .toScala
      .toList
      .flatMap(_.getElementsAs(_.expectStringNode.getValue).asScala)

  private def stringMap(node: ObjectNode, name: String): Map[String, String] =
    node
      .getObjectMember(name)
      .toScala
      .toList
      .flatMap(_.getStringMap.asScala.toList.map((k, v) => k -> v.expectStringNode.getValue))
      .toMap

}
