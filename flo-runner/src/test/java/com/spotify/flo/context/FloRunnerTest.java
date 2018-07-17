/*-
 * -\-\-
 * Flo Runner
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.flo.context;

import static com.spotify.flo.context.FloRunner.runTask;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.spotify.flo.EvalContext;
import com.spotify.flo.FloTesting;
import com.spotify.flo.Task;
import com.spotify.flo.TaskBuilder.F1;
import com.spotify.flo.TaskContextStrict;
import com.spotify.flo.TaskId;
import com.spotify.flo.TestContext;
import com.spotify.flo.TestScope;
import com.spotify.flo.Tracing;
import com.spotify.flo.context.FloRunner.Result;
import com.spotify.flo.freezer.Persisted;
import com.spotify.flo.freezer.PersistingContext;
import com.spotify.flo.status.NotReady;
import com.spotify.flo.status.NotRetriable;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FloRunnerTest {

  private static final Logger log = LoggerFactory.getLogger(FloRunnerTest.class);

  final Task<String> FOO_TASK = Task.named("task").ofType(String.class)
      .process(() -> "foo");

  private TerminationHook validTerminationHook;
  private TerminationHook exceptionalTerminationHook;

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp() {
    exceptionalTerminationHook = mock(TerminationHook.class);
    doThrow(new RuntimeException("hook exception")).when(exceptionalTerminationHook).accept(any());

    validTerminationHook = mock(TerminationHook.class);
    doNothing().when(validTerminationHook).accept(any());

    TestTerminationHookFactory.injectCreator((config) -> validTerminationHook);
  }

  @Test
  public void nonBlockingRunnerDoesNotBlock() throws Exception {
    final Path directory = temporaryFolder.newFolder().toPath();
    final Path startedFile = directory.resolve("started");
    final Path latchFile = directory.resolve("latch");
    final Path happenedFile = directory.resolve("happened");

    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            Files.write(startedFile, new byte[0]);
            while (true) {
              if (Files.exists(latchFile)) {
                Files.write(happenedFile, new byte[0]);
                return null;
              }
              Thread.sleep(100);
            }
          } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
          }
        });

    final Result<Void> result = runTask(task);

    // Verify that the task ran at all
    CompletableFuture.supplyAsync(() -> {
      while (true) {
        if (Files.exists(startedFile)) {
          return true;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).get(30, SECONDS);

    // Wait a little more to ensure that the task process has some time to write the "happened" file
    try {
      result.future().get(2, SECONDS);
      fail();
    } catch (TimeoutException ignore) {
    }

    // If this file doesn't exist now, it's likely that runTask doesn't block
    assertThat(Files.exists(happenedFile), is(false));

    Files.write(latchFile, new byte[0]);
  }

  @Test
  public void blockingRunnerBlocks() throws IOException {
    final Path file = temporaryFolder.newFile().toPath();

    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            Thread.sleep(10);
            try {
              Files.write(file, "hello".getBytes(UTF_8));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          return null;
        });

    runTask(task).waitAndExit(status -> { });

    assertThat(new String(Files.readAllBytes(file), UTF_8), is("hello"));
  }

  @Test
  public void valueIsPassedInFuture() throws Exception {
    final String result = runTask(FOO_TASK).future().get(30, SECONDS);

    assertThat(result, is("foo"));
  }

  @Test
  public void testSerializeException() throws Exception {
    final File file = temporaryFolder.newFile();
    file.delete();
    PersistingContext.serialize(new RuntimeException("foo"), file.toPath());
  }

  @Test
  public void exceptionsArePassed() throws Exception {
    final Task<String> task = Task.named("foo").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("foo");
        });

    Throwable exception = null;
    try {
      runTask(task).value();
    } catch (ExecutionException e) {
      exception = e.getCause();
    }
    assertThat(exception, is(instanceOf(RuntimeException.class)));
    assertThat(exception.getMessage(), is("foo"));
  }

  @Test
  public void persistedExitsZero() {
    final Task<Void> task = Task.named("persisted").ofType(Void.class)
        .process(() -> {
          throw new Persisted();
        });

    AtomicInteger status = new AtomicInteger(1);

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(0));
  }

  @Test
  public void valuesCanBeWaitedOn() throws Exception {
    final String result = runTask(FOO_TASK).value();

    assertThat(result, is("foo"));
  }

  @Test
  public void notReadyExitsTwenty() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new NotReady();
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(20));
  }

  @Test
  public void notRetriableExitsFifty() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new NotRetriable();
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(50));
  }

  @Test
  public void exceptionsExitNonZero() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(1));
  }

  @Test
  public void ignoreExceptionsFromTerminationHook() {
    TestTerminationHookFactory.injectHook(exceptionalTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(exceptionalTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskSuccess() {
    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskFailure() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(task).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(1));
    assertThat(status.get(), is(1));
  }

  @Test(expected = RuntimeException.class)
  public void failOnExceptionalTerminationHookFactory() {
    TestTerminationHookFactory.injectCreator((config) -> {
      throw new RuntimeException("factory exception");
    });
    runTask(FOO_TASK);
  }

  @Test
  public void taskIdIsInContext() throws Exception {
    final Task<TaskId> task = Task.named("task").ofType(TaskId.class)
        .process(() -> Tracing.TASK_ID.get());

    final Result<TaskId> result = runTask(task);

    assertThat(result.value(), is(task.id()));
  }

  @Test
  public void tasksRunInProcesses() throws Exception {

    final Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    final Instant yesterday = today.minus(1, ChronoUnit.DAYS);

    final Task<String> baz = Task.named("baz", today).ofType(String.class)
        .process(() -> {
          final String bazJvm = jvmName();
          log.info("baz: bazJvm={}, today={}", bazJvm, today);
          return bazJvm;
        });

    final Task<String[]> foo = Task.named("foo", yesterday).ofType(String[].class)
        .input(() -> baz)
        .process(bazJvm -> {
          final String fooJvm = jvmName();
          log.info("foo: fooJvm={}, bazJvm={}, yesterday={}", fooJvm, bazJvm, yesterday);
          return new String[]{bazJvm, fooJvm};
        });

    final Task<String> quux = Task.named("quux", today).ofType(String.class)
        .process(() -> {
          final String quuxJvm = jvmName();
          log.info("quux: quuxJvm={}, yesterday={}", quuxJvm, yesterday);
          return quuxJvm;
        });

    final Task<String[]> bar = Task.named("bar", today, yesterday).ofType(String[].class)
        .input(() -> foo)
        .input(() -> quux)
        .process((bazFooJvms, quuxJvm) -> {
          final String barJvm = jvmName();
          log.info("bar: barJvm={}, bazFooJvms={}, quuxJvm={} today={}, yesterday={}",
              barJvm, bazFooJvms, quuxJvm, today, yesterday);
          return Stream.concat(
              Stream.of(barJvm),
              Stream.concat(
                  Stream.of(bazFooJvms),
                  Stream.of(quuxJvm))
          ).toArray(String[]::new);
        });

    final List<String> jvms = Arrays.asList(runTask(bar).value());

    final String mainJvm = jvmName();

    log.info("main jvm: {}", mainJvm);
    log.info("task jvms: {}", jvms);
    final Set<String> uniqueJvms = new HashSet<>(jvms);
    assertThat(uniqueJvms.size(), is(4));
    assertThat(uniqueJvms, not(contains(mainJvm)));
  }

  @Test
  public void isTestShouldBeTrueInTestScope() throws Exception {
    try (TestScope ts = FloTesting.scope()) {
      final Task<Boolean> isTest = Task.named("task").ofType(Boolean.class)
          .process(FloTesting::isTest);
      assertThat(runTask(isTest).future().get(30, SECONDS), is(true));
    }
  }

  @Test
  public void testMocking() throws Exception {

    // Verify lookups and publishing after the fact
    try (TestScope ts = FloTesting.scope()) {
      final Task<String> task = Task.named("task").ofType(String.class)
          .context(new PublishingContext("foo"))
          .process(value -> {
            // Do some processing and produce a value
            return value.publish("42");
          });

      assertThat(runTask(task).future().get(30, SECONDS), is("42"));

      assertThat(PublishingContext.mock().lookups("foo"), is(1));
      assertThat(PublishingContext.mock().lookups("bar"), is(0));
      assertThat(PublishingContext.mock().published("foo"), contains("42"));
    }

    // Verify that all mocks are cleared when leaving scope
    try (TestScope ts = FloTesting.scope()) {
      assertThat(PublishingContext.mock().published("foo"), is(empty()));
      assertThat(PublishingContext.mock().lookups("foo"), is(0));
    }

    // Mock a lookup and verify that the process fn does not run
    try (TestScope ts = FloTesting.scope()) {
      PublishingContext.mock().mockValue("foo", "17");

      @SuppressWarnings("unchecked") final F1<PublishingContext.Value, String> mockProcessFn = Mockito.mock(F1.class);
      when(mockProcessFn.apply(any())).thenThrow(new AssertionError());

      final Task<String> task = Task.named("task").ofType(String.class)
          .context(new PublishingContext("foo"))
          .process(mockProcessFn);

      assertThat(runTask(task).future().get(30, SECONDS), is("17"));

      verify(mockProcessFn, never()).apply(any());

      assertThat(PublishingContext.mock().lookups("foo"), is(1));
      assertThat(PublishingContext.mock().published("foo"), is(empty()));
    }
  }

  static class PublishingContext extends TaskContextStrict<PublishingContext.Value, String> {

    private static final TestContext.Key<Mocking> MOCK = TestContext.key("publishing-context-mock", Mocking::new);

    private final String key;

    PublishingContext(String key) {
      this.key = key;
    }

    @Override
    public Value provide(EvalContext evalContext) {
      return new Value();
    }

    @Override
    public Optional<String> lookup(Task task) {
      if (FloTesting.isTest()) {
        return MOCK.get().lookup(key);
      } else {
        // Talk to some production service
        throw new UnsupportedOperationException();
      }
    }

    public static Mocking mock() {
      return MOCK.get();
    }

    class Value {

      String publish(String value) {
        if (FloTesting.isTest()) {
          return MOCK.get().publish(key, value);
        } else {
          // Talk to some production service
          throw new UnsupportedOperationException();
        }
      }
    }

    static class Mocking {

      private final ConcurrentMap<String, String> lookupValues = new ConcurrentHashMap<>();
      private final ConcurrentMap<String, AtomicInteger> lookupOperations = new ConcurrentHashMap<>();
      private final ConcurrentMap<String, ArrayDeque<String>> publishOperations = new ConcurrentHashMap<>();

      public void mockValue(String key, String value) {
        lookupValues.put(key, value);
      }

      Optional<String> lookup(String key) {
        lookupOperations.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        return Optional.ofNullable(lookupValues.get(key));
      }

      String publish(String key, String value) {
        publishOperations.computeIfAbsent(key, k -> new ArrayDeque<>()).add(value);
        return value;
      }

      public List<String> published(String key) {
        return Optional.ofNullable(publishOperations.get(key))
            .map(ImmutableList::copyOf)
            .orElse(ImmutableList.of());
      }

      public Optional<String> publishedLast(String key) {
        return Optional.ofNullable(Iterables.getLast(published(key), null));
      }

      public int lookups(String key) {
        return Optional.ofNullable(lookupOperations.get(key)).map(AtomicInteger::get).orElse(0);
      }
    }
  }

  private static String jvmName() {
    return ManagementFactory.getRuntimeMXBean().getName();
  }
}