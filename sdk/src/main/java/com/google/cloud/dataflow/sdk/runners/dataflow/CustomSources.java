/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.dataflow;

import static com.google.api.client.util.Base64.encodeBase64String;
import static com.google.cloud.dataflow.sdk.util.SerializableUtils.serializeToByteArray;
import static com.google.cloud.dataflow.sdk.util.Structs.addString;
import static com.google.cloud.dataflow.sdk.util.Structs.addStringList;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.services.dataflow.model.SourceMetadata;
import com.google.cloud.dataflow.sdk.io.BoundedSource;
import com.google.cloud.dataflow.sdk.io.Source;
import com.google.cloud.dataflow.sdk.io.UnboundedSource;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


/**
 * A helper class for supporting sources defined as {@code Source}.
 *
 * <p>Provides a bridge between the high-level {@code Source} API and the
 * low-level {@code CloudSource} class.
 */
public class CustomSources {
  private static final String SERIALIZED_SOURCE = "serialized_source";
  @VisibleForTesting static final String SERIALIZED_SOURCE_SPLITS = "serialized_source_splits";
  /**
   * The current limit on the size of a ReportWorkItemStatus RPC to Google Cloud Dataflow, which
   * includes the initial splits, is 20 MB.
   */
  public static final long DATAFLOW_SPLIT_RESPONSE_API_SIZE_BYTES = 20 * (1 << 20);

  private static final Logger LOG = LoggerFactory.getLogger(CustomSources.class);

  private static final ByteString firstSplitKey = ByteString.copyFromUtf8("0000000000000001");

  public static boolean isFirstUnboundedSourceSplit(ByteString splitKey) {
    return splitKey.equals(firstSplitKey);
  }

  private static int getDesiredNumUnboundedSourceSplits(DataflowPipelineOptions options) {
    if (options.getMaxNumWorkers() > 0) {
      return options.getMaxNumWorkers();
    } else if (options.getNumWorkers() > 0) {
      return options.getNumWorkers() * 3;
    } else {
      return 20;
    }
  }

  public static com.google.api.services.dataflow.model.Source serializeToCloudSource(
      Source<?> source, PipelineOptions options) throws Exception {
    com.google.api.services.dataflow.model.Source cloudSource =
        new com.google.api.services.dataflow.model.Source();
    // We ourselves act as the SourceFormat.
    cloudSource.setSpec(CloudObject.forClass(CustomSources.class));
    addString(
        cloudSource.getSpec(), SERIALIZED_SOURCE, encodeBase64String(serializeToByteArray(source)));

    SourceMetadata metadata = new SourceMetadata();
    if (source instanceof BoundedSource) {
      BoundedSource<?> boundedSource = (BoundedSource<?>) source;
      try {
        metadata.setProducesSortedKeys(boundedSource.producesSortedKeys(options));
      } catch (Exception e) {
        LOG.warn("Failed to check if the source produces sorted keys: " + source, e);
      }

      // Size estimation is best effort so we continue even if it fails here.
      try {
        metadata.setEstimatedSizeBytes(boundedSource.getEstimatedSizeBytes(options));
      } catch (Exception e) {
        LOG.warn("Size estimation of the source failed: " + source, e);
      }
    } else if (source instanceof UnboundedSource) {
      UnboundedSource<?, ?> unboundedSource = (UnboundedSource<?, ?>) source;
      metadata.setInfinite(true);
      List<String> encodedSplits = new ArrayList<>();
      int desiredNumSplits =
          getDesiredNumUnboundedSourceSplits(options.as(DataflowPipelineOptions.class));
      for (UnboundedSource<?, ?> split :
          unboundedSource.generateInitialSplits(desiredNumSplits, options)) {
        encodedSplits.add(encodeBase64String(serializeToByteArray(split)));
      }
      checkArgument(!encodedSplits.isEmpty(), "UnboundedSources must have at least one split");
      addStringList(cloudSource.getSpec(), SERIALIZED_SOURCE_SPLITS, encodedSplits);
    } else {
      throw new IllegalArgumentException("Unexpected source kind: " + source.getClass());
    }

    cloudSource.setMetadata(metadata);
    return cloudSource;
  }
}
