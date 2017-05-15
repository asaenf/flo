package io.rouz.flo.freezer;

import io.rouz.flo.TaskContext;
import io.rouz.flo.context.InstrumentedContext;
import io.rouz.flo.context.MemoizingContext;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A main class that uses {@link EvaluatingContext} for evaluating a specific task which has
 * been persisted by {@link PersistingContext}. It expects the inputs to the task to already
 * have been evaluated using the same method.
 *
 * <p>After evaluation of the specified task has completed, the output value will be stored as a
 * sibling file to the persisted task file, with an added "_out" suffix.
 */
public class TaskRunnerEntrypoint {

  private static final Logger LOG = LoggerFactory.getLogger(TaskRunnerEntrypoint.class);

  public static void main(String[] args) throws InterruptedException {
    if (args.length < 1) {
      LOG.info("Usage: flo-task-runner <persisted-task-file>");
      System.exit(1);
    }

    final String file = args[0];
    final URI fileUri = URI.create(file);
    final Path filePath = Paths.get(fileUri);

    final EvaluatingContext evaluatingContext = new EvaluatingContext(
        filePath.resolveSibling(""), MemoizingContext.composeWith(
            InstrumentedContext.composeWith(
                TaskContext.inmem(), new LoggingListener())));

    final TaskContext.Value<Object> value = evaluatingContext.evaluateTaskFrom(filePath);
    final CompletableFuture<Object> future = new CompletableFuture<>();
    value.consume(future::complete);
    value.onFail(future::completeExceptionally);

    try {
      future.get(24, TimeUnit.HOURS);
    } catch (ExecutionException | TimeoutException e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
}
