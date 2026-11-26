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
import weaver.Expectations
import weaver.SimpleIOSuite

/** The protocol's compliance tests (`smithy4s-ndjson-protocol-tests`), run against
  * `NdjsonRestJsonBuilder` as the server: one test per case that applies to a server.
  *
  * The client side of each case is for a client to run; this repository has none.
  */
object NdjsonRestJsonComplianceTests extends SimpleIOSuite {

  private def result(problems: IO[List[String]]): IO[Expectations] =
    problems.map {
      case Nil      => success
      case problems => failure(problems.mkString("\n"))
    }

  private val model = CaseModel.loadUnsafe()
  private val blobCases = ServerHarness.blobCases(model)
  private val eventStreamCases = ServerHarness.eventStreamCases(model)

  ServerHarness.smithy4sCases.foreach { t =>
    test(t.show)(t.run.map(_.fold(errors => failure(errors.toList.mkString("\n")), _ => success)))
  }

  blobCases.foreach((name, run) => test(name)(result(run)))

  eventStreamCases.foreach((name, run) => test(name)(result(run)))

  pureTest("every operation has server cases") {
    val covered =
      (ServerHarness.smithy4sCases.map(_.endpoint.name) ++
        blobCases.map(_._1.takeWhile(_ != '(')) ++
        eventStreamCases.map(_._1.takeWhile(_ != '('))).toSet
    expect.same(
      NdjsonRestJsonCompliance.endpoints.map(_.name).toSet.diff(covered),
      Set.empty[String],
    )
  }

}
