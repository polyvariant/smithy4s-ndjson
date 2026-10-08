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

package org.polyvariant.ndjson.openapi

import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import weaver.FunSuite

import scala.jdk.CollectionConverters.*

/** Asserts on the OpenAPI spec this build's own smithy4s codegen writes for the fixtures'
  * `TestService`, with the extension on its model path — so on exactly what a user gets, from a
  * code generator running alloy's Scala 2.12 build while the extension was compiled against its
  * Scala 3 one.
  */
object NdjsonRestJsonOpenApiTests extends FunSuite {

  private val spec: ObjectNode = {
    val resource = "org.polyvariant.ndjson.test.TestService.json"
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource)).getOrElse(
      sys.error(s"$resource was not generated: the extension was not picked up by codegen")
    )
    try Node.parse(stream).expectObjectNode()
    finally stream.close()
  }

  private val binary: ObjectNode = Node
    .objectNode()
    .withMember("type", "string")
    .withMember("format", "binary")

  private def ref(name: String): ObjectNode = Node
    .objectNode()
    .withMember("$ref", s"#/components/schemas/$name")

  private def operation(path: String, method: String): ObjectNode = spec
    .expectObjectMember("paths")
    .expectObjectMember(path)
    .expectObjectMember(method)

  private def request(path: String, method: String): Map[String, Node] = schemasByMediaType(
    operation(path, method).expectObjectMember("requestBody")
  )

  private def response(path: String, method: String, status: String): Map[String, Node] =
    schemasByMediaType(
      operation(path, method).expectObjectMember("responses").expectObjectMember(status)
    )

  private def schemasByMediaType(body: ObjectNode): Map[String, Node] =
    body
      .expectObjectMember("content")
      .getStringMap
      .asScala
      .map((mediaType, described) =>
        mediaType -> described.expectObjectNode().expectMember("schema")
      )
      .toMap

  test("a streamed union output is application/x-ndjson, described one line at a time") {
    expect(clue(response("/echo", "post", "200")) == Map("application/x-ndjson" -> ref("Event")))
  }

  test("a streamed union input is application/x-ndjson, described one line at a time") {
    expect(clue(request("/ingest", "post")) == Map("application/x-ndjson" -> ref("Command")))
  }

  test("a streamed blob output is application/octet-stream, as binary rather than base64") {
    expect(
      clue(response("/download/{name}", "get", "200")) == Map("application/octet-stream" -> binary)
    )
  }

  test("a streamed blob input is application/octet-stream, as binary rather than base64") {
    expect(clue(request("/upload", "post")) == Map("application/octet-stream" -> binary))
  }

  test("a streamed blob with a @mediaType is described as that type") {
    expect(clue(response("/report", "get", "200").keySet) == Set("text/csv"))
  }

  test("a streamed blob keeps its documentation, as its schema's description") {
    expect(
      clue(response("/report", "get", "200").get("text/csv")) ==
        Some(binary.withMember("description", "The report's rows, as CSV."))
    )
  }

  test("a streamed response is found under the status from @http(code:)") {
    expect(
      clue(response("/tagged/{tag}", "post", "202")) == Map("application/x-ndjson" -> ref("Event"))
    )
  }

  test("what does not stream is described exactly as alloy describes simpleRestJson") {
    expect(
      clue(response("/greet/{name}", "get", "200")) ==
        Map("application/json" -> ref("GreetResponseContent"))
    ) &&
    expect(
      clue(response("/fallible/{which}", "get", "404")) ==
        Map("application/json" -> ref("NotThereResponseContent"))
    )
  }

}
