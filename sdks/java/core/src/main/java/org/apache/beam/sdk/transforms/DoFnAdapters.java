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
package org.apache.beam.sdk.transforms;

import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.io.IOException;

/**
 * Utility class containing adapters for running a {@link DoFn} as an {@link OldDoFn}.
 *
 * @deprecated This class will go away when we start running {@link DoFn}'s directly (using
 * {@link DoFnInvoker}) rather than via {@link OldDoFn}.
 */
@Deprecated
public class DoFnAdapters {
  /** Should not be instantiated. */
  private DoFnAdapters() {}

  /**
   * If this is an {@link OldDoFn} produced via {@link #toOldDoFn}, returns the class of the
   * original {@link DoFn}, otherwise returns {@code fn.getClass()}.
   */
  public static Class<?> getDoFnClass(OldDoFn<?, ?> fn) {
    if (fn instanceof SimpleDoFnAdapter) {
      return ((SimpleDoFnAdapter<?, ?>) fn).fn.getClass();
    } else {
      return fn.getClass();
    }
  }

  /** Creates an {@link OldDoFn} that delegates to the {@link DoFn}. */
  public static <InputT, OutputT> OldDoFn<InputT, OutputT> toOldDoFn(DoFn<InputT, OutputT> fn) {
    DoFnSignature signature = DoFnSignatures.INSTANCE.getOrParseSignature(fn.getClass());
    if (signature.processElement().usesSingleWindow()) {
      return new WindowDoFnAdapter<>(fn);
    } else {
      return new SimpleDoFnAdapter<>(fn);
    }
  }

  /**
   * Wraps a {@link DoFn} that doesn't require access to {@link BoundedWindow} as an {@link
   * OldDoFn}.
   */
  private static class SimpleDoFnAdapter<InputT, OutputT> extends OldDoFn<InputT, OutputT> {
    private final DoFn<InputT, OutputT> fn;
    private transient DoFnInvoker<InputT, OutputT> invoker;

    SimpleDoFnAdapter(DoFn<InputT, OutputT> fn) {
      super(fn.aggregators);
      this.fn = fn;
      this.invoker = DoFnInvokers.INSTANCE.newByteBuddyInvoker(fn);
    }

    @Override
    public void setup() throws Exception {
      this.invoker.invokeSetup();
    }

    @Override
    public void startBundle(Context c) throws Exception {
      this.fn.prepareForProcessing();
      invoker.invokeStartBundle(new ContextAdapter<>(fn, c));
    }

    @Override
    public void finishBundle(Context c) throws Exception {
      invoker.invokeFinishBundle(new ContextAdapter<>(fn, c));
    }

    @Override
    public void teardown() throws Exception {
      this.invoker.invokeTeardown();
    }

    @Override
    public void processElement(ProcessContext c) throws Exception {
      ProcessContextAdapter<InputT, OutputT> adapter = new ProcessContextAdapter<>(fn, c);
      invoker.invokeProcessElement(adapter, adapter);
    }

    @Override
    protected TypeDescriptor<InputT> getInputTypeDescriptor() {
      return fn.getInputTypeDescriptor();
    }

    @Override
    protected TypeDescriptor<OutputT> getOutputTypeDescriptor() {
      return fn.getOutputTypeDescriptor();
    }

    @Override
    public Duration getAllowedTimestampSkew() {
      return fn.getAllowedTimestampSkew();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.include(fn);
    }

    private void readObject(java.io.ObjectInputStream in)
        throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      this.invoker = DoFnInvokers.INSTANCE.newByteBuddyInvoker(fn);
    }
  }

  /** Wraps a {@link DoFn} that requires access to {@link BoundedWindow} as an {@link OldDoFn}. */
  private static class WindowDoFnAdapter<InputT, OutputT> extends SimpleDoFnAdapter<InputT, OutputT>
      implements OldDoFn.RequiresWindowAccess {

    WindowDoFnAdapter(DoFn<InputT, OutputT> fn) {
      super(fn);
    }
  }

  /**
   * Wraps an {@link OldDoFn.Context} as a {@link DoFn.ExtraContextFactory} inside a {@link
   * DoFn.StartBundle} or {@link DoFn.FinishBundle} method, which means the extra context is
   * unavailable.
   */
  private static class ContextAdapter<InputT, OutputT> extends DoFn<InputT, OutputT>.Context
      implements DoFn.ExtraContextFactory<InputT, OutputT> {

    private OldDoFn<InputT, OutputT>.Context context;

    private ContextAdapter(DoFn<InputT, OutputT> fn, OldDoFn<InputT, OutputT>.Context context) {
      fn.super();
      this.context = context;
    }

    @Override
    public PipelineOptions getPipelineOptions() {
      return context.getPipelineOptions();
    }

    @Override
    public void output(OutputT output) {
      context.output(output);
    }

    @Override
    public void outputWithTimestamp(OutputT output, Instant timestamp) {
      context.outputWithTimestamp(output, timestamp);
    }

    @Override
    public <T> void sideOutput(TupleTag<T> tag, T output) {
      context.sideOutput(tag, output);
    }

    @Override
    public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      context.sideOutputWithTimestamp(tag, output, timestamp);
    }

    @Override
    public BoundedWindow window() {
      // The DoFn doesn't allow us to ask for these outside ProcessElements, so this
      // should be unreachable.
      throw new UnsupportedOperationException("Can only get the window in ProcessElements");
    }

    @Override
    public DoFn.InputProvider<InputT> inputProvider() {
      throw new UnsupportedOperationException("inputProvider() exists only for testing");
    }

    @Override
    public DoFn.OutputReceiver<OutputT> outputReceiver() {
      throw new UnsupportedOperationException("outputReceiver() exists only for testing");
    }
  }

  /**
   * Wraps an {@link OldDoFn.ProcessContext} as a {@link DoFn.ExtraContextFactory} inside a {@link
   * DoFn.ProcessElement} method.
   */
  private static class ProcessContextAdapter<InputT, OutputT>
      extends DoFn<InputT, OutputT>.ProcessContext
      implements DoFn.ExtraContextFactory<InputT, OutputT> {

    private OldDoFn<InputT, OutputT>.ProcessContext context;

    private ProcessContextAdapter(
        DoFn<InputT, OutputT> fn, OldDoFn<InputT, OutputT>.ProcessContext context) {
      fn.super();
      this.context = context;
    }

    @Override
    public PipelineOptions getPipelineOptions() {
      return context.getPipelineOptions();
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view) {
      return context.sideInput(view);
    }

    @Override
    public void output(OutputT output) {
      context.output(output);
    }

    @Override
    public void outputWithTimestamp(OutputT output, Instant timestamp) {
      context.outputWithTimestamp(output, timestamp);
    }

    @Override
    public <T> void sideOutput(TupleTag<T> tag, T output) {
      context.sideOutput(tag, output);
    }

    @Override
    public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      context.sideOutputWithTimestamp(tag, output, timestamp);
    }

    @Override
    public InputT element() {
      return context.element();
    }

    @Override
    public Instant timestamp() {
      return context.timestamp();
    }

    @Override
    public PaneInfo pane() {
      return context.pane();
    }

    @Override
    public BoundedWindow window() {
      return context.window();
    }

    @Override
    public DoFn.InputProvider<InputT> inputProvider() {
      throw new UnsupportedOperationException("inputProvider() exists only for testing");
    }

    @Override
    public DoFn.OutputReceiver<OutputT> outputReceiver() {
      throw new UnsupportedOperationException("outputReceiver() exists only for testing");
    }
  }
}
