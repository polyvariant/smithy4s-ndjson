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

import fs2.Stream

/** Kind-5 view of a smithy service that keeps `@streaming` members visible in the method
  * signatures.
  *
  * smithy4s's default `FunctorAlgebra[Alg, F]` view is kind-1: it erases `SI` and `SO`, which is
  * why `SimpleRestJsonBuilder.routes` cannot express a streaming operation at all. This alias keeps
  * both, so an operation reads as
  *
  * {{{
  * Stream[F, SI] => F[(O, Stream[F, SO])]
  * }}}
  *
  * where `SI` is the streamed request body (`Nothing` when the operation doesn't stream one in),
  * `O` is the non-streaming output envelope, and `SO` is the streamed response element (`Nothing`
  * when the operation doesn't stream one out). Non-streaming operations therefore appear as
  * `Stream[F, Nothing] => F[(O, Stream[F, Nothing])]` — slightly noisier than a plain `I => F[O]`,
  * but it lets one algebra carry both kinds of operation, which is what allows a service to mix
  * them.
  *
  * The `F[...]` sits outside the tuple on purpose: it is the effect of *starting* the operation
  * (validating input, opening resources, producing the envelope), and it completes before the
  * response stream is drained. Anything that must be resolved while the caller's request scope is
  * still live belongs there rather than inside the returned stream — see the scope note on
  * [[NdjsonRestJsonBuilder]].
  */
type WithStreamedIO[F[_]] = [I, E, O, SI, SO] =>> Stream[F, SI] => F[(O, Stream[F, SO])]
