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

import alloy.openapi.AlloyOpenApiProtocol;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.polyvariant.ndjson.NdjsonRestJsonTrait;
import software.amazon.smithy.jsonschema.Schema;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.openapi.OpenApiConfig;
import software.amazon.smithy.openapi.fromsmithy.Context;
import software.amazon.smithy.openapi.fromsmithy.OpenApiProtocol;
import software.amazon.smithy.openapi.model.MediaTypeObject;
import software.amazon.smithy.openapi.model.OperationObject;
import software.amazon.smithy.openapi.model.ResponseObject;

/**
 * Describes an {@code ndjsonRestJson} service in OpenAPI.
 *
 * <p>Everything that does not stream follows {@code alloy#simpleRestJson} exactly, so each
 * operation is first described exactly as alloy describes {@code simpleRestJson} — by alloy's own
 * {@link AlloyOpenApiProtocol}. A {@code @streaming} payload is then relabelled per this protocol's
 * framing, which alloy knows nothing of:
 *
 * <ul>
 *   <li>a {@code @streaming union} as {@code application/x-ndjson}, its schema being that of a
 *       single line;
 *   <li>a {@code @streaming blob} as its {@code @mediaType}, or {@code application/octet-stream},
 *       with a binary schema in place of the base64 string alloy writes for a JSON blob.
 * </ul>
 *
 * <p>alloy is delegated to rather than extended. Its base class is Scala, built once per Scala
 * version, and this runs inside whatever performs the conversion — sbt 1, sbt 2, Mill, the smithy4s
 * CLI — each on its own Scala build of alloy. Calling it only through the Java signatures of
 * {@link OpenApiProtocol}, which every one of those builds shares, keeps this artifact free of any
 * Scala version.
 */
final class NdjsonRestJsonOpenApiProtocol implements OpenApiProtocol<NdjsonRestJsonTrait> {

  private static final String NDJSON = "application/x-ndjson";

  private static final String OCTET_STREAM = "application/octet-stream";

  private static final Schema BINARY = Schema.builder().type("string").format("binary").build();

  /**
   * Raw, because alloy's protocol is typed for its own trait. That type is never acted on: alloy
   * builds an operation from the context's model, service and schema converter, and never reads the
   * protocol trait it carries.
   */
  @SuppressWarnings("rawtypes")
  private final OpenApiProtocol alloy = new AlloyOpenApiProtocol();

  @Override
  public Class<NdjsonRestJsonTrait> getProtocolType() {
    return NdjsonRestJsonTrait.class;
  }

  @Override
  public void updateDefaultSettings(Model model, OpenApiConfig config) {
    alloy.updateDefaultSettings(model, config);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<Operation> createOperation(
    Context<NdjsonRestJsonTrait> context,
    OperationShape operation
  ) {
    Optional<Operation> described = alloy.createOperation(context, operation);
    return described.map(entry -> relabelStreamedPayloads(context, operation, entry));
  }

  private Operation relabelStreamedPayloads(
    Context<NdjsonRestJsonTrait> context,
    OperationShape operation,
    Operation entry
  ) {
    Model model = context.getModel();
    HttpBindingIndex bindings = HttpBindingIndex.of(model);
    OperationObject described = entry.getOperation().build();
    OperationObject.Builder relabelled = described.toBuilder();

    streamedPayload(model, bindings.getRequestBindings(operation, HttpBinding.Location.PAYLOAD))
      .ifPresent(streamed ->
        described
          .getRequestBody()
          .ifPresent(body ->
            relabelled.requestBody(
              body.toBuilder().content(streamed.relabel(body.getContent())).build()
            )
          )
      );

    // Only the operation's own response can stream: an error is never streamed.
    streamedPayload(model, bindings.getResponseBindings(operation, HttpBinding.Location.PAYLOAD))
      .ifPresent(streamed -> {
        String status = getOperationResponseStatusCode(context, operation);
        ResponseObject response = described.getResponses().get(status);
        if (response != null) {
          relabelled.putResponse(
            status,
            response.toBuilder().content(streamed.relabel(response.getContent())).build()
          );
        }
      });

    return Operation.create(entry.getMethod(), entry.getUri(), relabelled);
  }

  /** How the given payload binding is framed, if it is a {@code @streaming} one. */
  private static Optional<Streamed> streamedPayload(Model model, List<HttpBinding> payload) {
    return payload
      .stream()
      .findFirst()
      .map(binding -> model.expectShape(binding.getMember().getTarget()))
      .filter(target -> target.hasTrait(StreamingTrait.class))
      .map(NdjsonRestJsonOpenApiProtocol::framingOf);
  }

  /**
   * Smithy restricts {@code @streaming} to {@code :is(blob, union)}, so anything that is not a blob
   * is a union.
   */
  private static Streamed framingOf(Shape target) {
    if (target.isBlobShape()) {
      String mediaType = target
        .getTrait(MediaTypeTrait.class)
        .map(MediaTypeTrait::getValue)
        .orElse(OCTET_STREAM);
      return new Streamed(mediaType, Optional.of(BINARY));
    }
    return new Streamed(NDJSON, Optional.empty());
  }

  /** A streamed payload's media type, and the schema to describe it with if alloy's does not fit. */
  private static final class Streamed {

    private final String mediaType;

    private final Optional<Schema> schema;

    Streamed(String mediaType, Optional<Schema> schema) {
      this.mediaType = mediaType;
      this.schema = schema;
    }

    /** alloy writes a payload as a single entry, under its document media type. */
    Map<String, MediaTypeObject> relabel(Map<String, MediaTypeObject> content) {
      return content
        .values()
        .stream()
        .findFirst()
        .map(described -> schema.map(s -> described.toBuilder().schema(s).build()).orElse(described))
        .map(described -> Map.of(mediaType, described))
        .orElse(content);
    }

  }

}
