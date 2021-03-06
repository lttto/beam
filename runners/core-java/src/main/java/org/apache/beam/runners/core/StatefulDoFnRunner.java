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
package org.apache.beam.runners.core;

import java.util.Map;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.NonMergingWindowFn;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.TimeDomain;
import org.apache.beam.sdk.util.WindowTracing;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.util.state.State;
import org.apache.beam.sdk.util.state.StateSpec;
import org.joda.time.Instant;

/**
 * A customized {@link DoFnRunner} that handles late data dropping and garbage collection for
 * stateful {@link DoFn DoFns}. It registers a GC timer in {@link #processElement(WindowedValue)}
 * and does cleanup in {@link #onTimer(String, BoundedWindow, Instant, TimeDomain)}
 *
 * @param <InputT> the type of the {@link DoFn} (main) input elements
 * @param <OutputT> the type of the {@link DoFn} (main) output elements
 */
public class StatefulDoFnRunner<InputT, OutputT, W extends BoundedWindow>
    implements DoFnRunner<InputT, OutputT> {

  public static final String GC_TIMER_ID = "__StatefulParDoGcTimerId";
  public static final String DROPPED_DUE_TO_LATENESS_COUNTER = "StatefulParDoDropped";

  private final DoFnRunner<InputT, OutputT> doFnRunner;
  private final WindowingStrategy<?, ?> windowingStrategy;
  private final Aggregator<Long, Long> droppedDueToLateness;
  private final CleanupTimer cleanupTimer;
  private final StateCleaner stateCleaner;

  public StatefulDoFnRunner(
      DoFnRunner<InputT, OutputT> doFnRunner,
      WindowingStrategy<?, ?> windowingStrategy,
      CleanupTimer cleanupTimer,
      StateCleaner<W> stateCleaner,
      Aggregator<Long, Long> droppedDueToLateness) {
    this.doFnRunner = doFnRunner;
    this.windowingStrategy = windowingStrategy;
    this.cleanupTimer = cleanupTimer;
    this.stateCleaner = stateCleaner;
    WindowFn<?, ?> windowFn = windowingStrategy.getWindowFn();
    rejectMergingWindowFn(windowFn);
    this.droppedDueToLateness = droppedDueToLateness;
  }

  private void rejectMergingWindowFn(WindowFn<?, ?> windowFn) {
    if (!(windowFn instanceof NonMergingWindowFn)) {
      throw new UnsupportedOperationException(
          "MergingWindowFn is not supported for stateful DoFns, WindowFn is: "
              + windowFn);
    }
  }

  @Override
  public void startBundle() {
    doFnRunner.startBundle();
  }

  @Override
  public void processElement(WindowedValue<InputT> compressedElem) {

    // StatefulDoFnRunner always observes windows, so we need to explode
    for (WindowedValue<InputT> value : compressedElem.explodeWindows()) {

      BoundedWindow window = value.getWindows().iterator().next();

      if (!dropLateData(window)) {
        cleanupTimer.setForWindow(window);
        doFnRunner.processElement(value);
      }
    }
  }

  private boolean dropLateData(BoundedWindow window) {
    Instant gcTime = window.maxTimestamp().plus(windowingStrategy.getAllowedLateness());
    Instant inputWM = cleanupTimer.currentInputWatermarkTime();
    if (gcTime.isBefore(inputWM)) {
      // The element is too late for this window.
      droppedDueToLateness.addValue(1L);
      WindowTracing.debug(
          "StatefulDoFnRunner.processElement/onTimer: Dropping element for window:{} "
              + "since too far behind inputWatermark:{}", window, inputWM);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void onTimer(
      String timerId, BoundedWindow window, Instant timestamp, TimeDomain timeDomain) {
    boolean isEventTimer = timeDomain.equals(TimeDomain.EVENT_TIME);
    Instant gcTime = window.maxTimestamp().plus(windowingStrategy.getAllowedLateness());
    if (isEventTimer && GC_TIMER_ID.equals(timerId) && gcTime.equals(timestamp)) {
      stateCleaner.clearForWindow(window);
      // There should invoke the onWindowExpiration of DoFn
    } else {
      if (isEventTimer || !dropLateData(window)) {
        doFnRunner.onTimer(timerId, window, timestamp, timeDomain);
      }
    }
  }

  @Override
  public void finishBundle() {
    doFnRunner.finishBundle();
  }

  /**
   * A cleaner for deciding when to clean state of window.
   *
   * <p>A runner might either (a) already know that it always has a timer set
   * for the expiration time or (b) not need a timer at all because it is
   * a batch runner that discards state when it is done.
   */
  public interface CleanupTimer {

    /**
     * Return the current, local input watermark timestamp for this computation
     * in the {@link TimeDomain#EVENT_TIME} time domain.
     */
    Instant currentInputWatermarkTime();

    /**
     * Set the garbage collect time of the window to timer.
     */
    void setForWindow(BoundedWindow window);
  }

  /**
   * A cleaner to clean all states of the window.
   */
  public interface StateCleaner<W extends BoundedWindow> {

    void clearForWindow(W window);
  }

  /**
   * A {@link CleanupTimer} implemented by TimerInternals.
   */
  public static class TimeInternalsCleanupTimer implements CleanupTimer {

    private final TimerInternals timerInternals;
    private final WindowingStrategy<?, ?> windowingStrategy;
    private final Coder<BoundedWindow> windowCoder;

    public TimeInternalsCleanupTimer(
        TimerInternals timerInternals,
        WindowingStrategy<?, ?> windowingStrategy) {
      this.windowingStrategy = windowingStrategy;
      WindowFn<?, ?> windowFn = windowingStrategy.getWindowFn();
      windowCoder = (Coder<BoundedWindow>) windowFn.windowCoder();
      this.timerInternals = timerInternals;
    }

    @Override
    public Instant currentInputWatermarkTime() {
      return timerInternals.currentInputWatermarkTime();
    }

    @Override
    public void setForWindow(BoundedWindow window) {
      Instant gcTime = window.maxTimestamp().plus(windowingStrategy.getAllowedLateness());
      timerInternals.setTimer(StateNamespaces.window(windowCoder, window),
          GC_TIMER_ID, gcTime, TimeDomain.EVENT_TIME);
    }

  }

  /**
   * A {@link StateCleaner} implemented by StateInternals.
   */
  public static class StateInternalsStateCleaner<W extends BoundedWindow>
      implements StateCleaner<W> {

    private final DoFn<?, ?> fn;
    private final DoFnSignature signature;
    private final StateInternals<?> stateInternals;
    private final Coder<W> windowCoder;

    public StateInternalsStateCleaner(
        DoFn<?, ?> fn,
        StateInternals<?> stateInternals,
        Coder<W> windowCoder) {
      this.fn = fn;
      this.signature = DoFnSignatures.getSignature(fn.getClass());
      this.stateInternals = stateInternals;
      this.windowCoder = windowCoder;
    }

    @Override
    public void clearForWindow(W window) {
      for (Map.Entry<String, DoFnSignature.StateDeclaration> entry :
          signature.stateDeclarations().entrySet()) {
        try {
          StateSpec<?, ?> spec = (StateSpec<?, ?>) entry.getValue().field().get(fn);
          State state = stateInternals.state(StateNamespaces.window(windowCoder, window),
              StateTags.tagForSpec(entry.getKey(), (StateSpec) spec));
          state.clear();
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

}
