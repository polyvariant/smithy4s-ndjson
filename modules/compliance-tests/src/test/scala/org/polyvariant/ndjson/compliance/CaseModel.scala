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

import smithy4s.Document
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.StreamingTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.protocoltests.traits.AppliesTo
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestCase
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestsTrait

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The compliance tests' model as Smithy itself reads it, for the one kind of case codegen can't
  * carry: `@eventStreamTests` (see this module's `codegen.smithy`).
  */
object CaseModel {

  val protocol: ShapeId = ShapeId.from("org.polyvariant.ndjson#ndjsonRestJson")

  /** Loads every model on the test classpath, which includes `protocolTests`' jar. */
  def loadUnsafe(): Model = {
    val loader = getClass.getClassLoader
    Model.assembler(loader).discoverModels(loader).assemble().unwrap()
  }

  /** Every `@eventStreamTests` case for this protocol that a server runs, with its operation. */
  def serverEventStreamCases(model: Model): List[(OperationShape, EventStreamTestCase)] =
    model
      .getOperationShapes
      .asScala
      .toList
      .sortBy(_.getId.toString)
      .flatMap { op =>
        op.getTrait(classOf[EventStreamTestsTrait])
          .toScala
          .toList
          .flatMap(_.getTestCases.asScala)
          .filter(tc =>
            tc.getProtocol == protocol && !tc.getAppliesTo.toScala.contains(AppliesTo.CLIENT)
          )
          .map(op -> _)
      }

  /** The member of `structure` that is streamed: the one targeting a `@streaming` shape.
    *
    * Not smithy4s's `StreamingSchema.fieldName`, which holds the structure's name instead.
    */
  def streamedMember(model: Model, structure: ShapeId): Option[MemberShape] =
    model
      .expectShape(structure)
      .members
      .asScala
      .find(m => model.expectShape(m.getTarget).hasTrait(classOf[StreamingTrait]))

  /** A case's `params`, read against `shape`, as the document [[ParamsDecoder]] reads.
    *
    * The two agree on everything but timestamps. `@eventStreamTests` has them in their member's
    * `@timestampFormat` — Smithy's validator insists — while the decoder only reads epoch seconds, as
    * `@httpRequestTests` has them. So a formatted timestamp is converted here, which takes the shape
    * to know which format it is in.
    */
  def paramsDocument(model: Model, node: Node, shape: Shape): Document =
    read(model, node, shape, memberFormat = None)

  private def read(model: Model, node: Node, shape: Shape, memberFormat: Option[String])
    : Document =
    shape match {
      case member: MemberShape =>
        read(model, node, model.expectShape(member.getTarget), formatOf(member))

      case _: TimestampShape if node.isStringNode =>
        val value = node.expectStringNode.getValue
        val instant =
          memberFormat.orElse(formatOf(shape)) match {
            case Some(TimestampFormatTrait.HTTP_DATE) =>
              ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
            case _ => Instant.parse(value)
          }
        Document.DNumber(BigDecimal(instant.getEpochSecond) + BigDecimal(instant.getNano, 9))

      case s: StructureShape if node.isObjectNode => members(model, node, s.getMember(_).toScala)
      case u: UnionShape if node.isObjectNode     => members(model, node, u.getMember(_).toScala)

      case l: ListShape if node.isArrayNode =>
        Document.array(
          node.expectArrayNode.getElements.asScala.map(read(model, _, l.getMember, None))
        )

      case m: MapShape if node.isObjectNode => members(model, node, _ => Some(m.getValue))

      case _ => plain(node)
    }

  /** An object's members, each read against its own shape, or as-is if the shape has none (an
    * unknown member, kept by `@jsonUnknown`).
    */
  private def members(model: Model, node: Node, memberShape: String => Option[MemberShape])
    : Document =
    Document.obj(node.expectObjectNode.getStringMap.asScala.toList.map { (name, value) =>
      name -> memberShape(name).fold(plain(value))(read(model, value, _, None))
    })

  private def formatOf(shape: Shape): Option[String] =
    shape.getTrait(classOf[TimestampFormatTrait]).toScala.map(_.getValue)

  private def plain(node: Node): Document =
    if (node.isObjectNode)
      Document.obj(node.expectObjectNode.getStringMap.asScala.toList.map((k, v) => k -> plain(v)))
    else if (node.isArrayNode)
      Document.array(node.expectArrayNode.getElements.asScala.map(plain))
    else if (node.isStringNode)
      Document.fromString(node.expectStringNode.getValue)
    else if (node.isNumberNode)
      Document.DNumber(BigDecimal(node.expectNumberNode.getValue.toString))
    else if (node.isBooleanNode)
      Document.fromBoolean(node.expectBooleanNode.getValue)
    else
      Document.DNull

}
