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

package org.polyvariant.ndjson.openapi;

import java.util.List;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.openapi.fromsmithy.OpenApiProtocol;
import software.amazon.smithy.openapi.fromsmithy.Smithy2OpenApiExtension;

/**
 * Teaches Smithy's OpenAPI conversion the {@code org.polyvariant.ndjson#ndjsonRestJson} protocol.
 *
 * <p>Registered in {@code META-INF/services}, which is how alloy's conversion — the one smithy4s
 * runs during codegen — finds the protocols it can describe: alloy picks a service's protocol by
 * looking for one of their traits on it, so with this jar on the codegen model path, every
 * {@code @ndjsonRestJson} service gets a spec of its own.
 */
public final class NdjsonRestJsonOpenApiExtension implements Smithy2OpenApiExtension {

  @Override
  public List<OpenApiProtocol<? extends Trait>> getProtocols() {
    return List.of(new NdjsonRestJsonOpenApiProtocol());
  }

}
