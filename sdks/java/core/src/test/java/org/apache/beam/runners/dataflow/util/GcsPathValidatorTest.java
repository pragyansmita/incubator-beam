/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.util;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.GcsOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.util.GcsPathValidator;
import org.apache.beam.sdk.util.GcsUtil;
import org.apache.beam.sdk.util.TestCredential;
import org.apache.beam.sdk.util.gcsfs.GcsPath;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link GcsPathValidator}. */
@RunWith(JUnit4.class)
public class GcsPathValidatorTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Mock private GcsUtil mockGcsUtil;
  private GcsPathValidator validator;

  private class FakeRunner extends PipelineRunner<PipelineResult> {
    @Override
    public PipelineResult run(Pipeline pipeline) {
      throw new UnsupportedOperationException();
    }
  }

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(mockGcsUtil.bucketExists(any(GcsPath.class))).thenReturn(true);
    when(mockGcsUtil.isGcsPatternSupported(anyString())).thenCallRealMethod();
    GcsOptions options = PipelineOptionsFactory.as(GcsOptions.class);
    options.setRunner(FakeRunner.class);
    options.setGcpCredential(new TestCredential());
    options.setGcsUtil(mockGcsUtil);
    validator = GcsPathValidator.fromOptions(options);
  }

  @Test
  public void testValidFilePattern() {
    validator.validateInputFilePatternSupported("gs://bucket/path");
  }

  @Test
  public void testInvalidFilePattern() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "FakeRunner expected a valid 'gs://' path but was given '/local/path'");
    validator.validateInputFilePatternSupported("/local/path");
  }

  @Test
  public void testWhenBucketDoesNotExist() throws Exception {
    when(mockGcsUtil.bucketExists(any(GcsPath.class))).thenReturn(false);
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "Could not find file gs://non-existent-bucket/location");
    validator.validateInputFilePatternSupported("gs://non-existent-bucket/location");
  }

  @Test
  public void testValidOutputPrefix() {
    validator.validateOutputFilePrefixSupported("gs://bucket/path");
  }

  @Test
  public void testInvalidOutputPrefix() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
        "FakeRunner expected a valid 'gs://' path but was given '/local/path'");
    validator.validateOutputFilePrefixSupported("/local/path");
  }
}
